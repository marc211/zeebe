/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.nwe.behavior;

import static io.zeebe.util.buffer.BufferUtil.bufferAsString;

import io.zeebe.engine.nwe.BpmnElementContext;
import io.zeebe.engine.nwe.BpmnProcessingException;
import io.zeebe.engine.processor.Failure;
import io.zeebe.engine.processor.KeyGenerator;
import io.zeebe.engine.processor.TypedStreamWriter;
import io.zeebe.engine.processor.workflow.CatchEventBehavior;
import io.zeebe.engine.processor.workflow.ExpressionProcessor.EvaluationException;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableActivity;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableBoundaryEvent;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableCatchEventSupplier;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableEventBasedGateway;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableFlowNode;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableReceiveTask;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableSequenceFlow;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableStartEvent;
import io.zeebe.engine.processor.workflow.message.MessageCorrelationKeyException;
import io.zeebe.engine.processor.workflow.message.MessageNameException;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.deployment.DeployedWorkflow;
import io.zeebe.engine.state.deployment.WorkflowState;
import io.zeebe.engine.state.instance.ElementInstance;
import io.zeebe.engine.state.instance.ElementInstanceState;
import io.zeebe.engine.state.instance.EventScopeInstanceState;
import io.zeebe.engine.state.instance.EventTrigger;
import io.zeebe.engine.state.instance.StoredRecord.Purpose;
import io.zeebe.engine.state.instance.VariablesState;
import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import io.zeebe.protocol.record.value.ErrorType;
import io.zeebe.util.Either;
import java.util.function.ToLongFunction;

public final class BpmnEventSubscriptionBehavior {

  private static final String NO_WORKFLOW_FOUND_MESSAGE =
      "Expected to create an instance of workflow with key '%d', but no such workflow was found";
  private static final String NO_TRIGGERED_EVENT_MESSAGE =
      "Expected to create an instance of workflow with key '%d', but no triggered event could be found";

  private final WorkflowInstanceRecord eventRecord = new WorkflowInstanceRecord();
  private final WorkflowInstanceRecord recordForWFICreation = new WorkflowInstanceRecord();

  private final BpmnStateBehavior stateBehavior;
  private final BpmnStateTransitionBehavior stateTransitionBehavior;
  private final EventScopeInstanceState eventScopeInstanceState;
  private final ElementInstanceState elementInstanceState;
  private final CatchEventBehavior catchEventBehavior;
  private final TypedStreamWriter streamWriter;
  private final KeyGenerator keyGenerator;
  private final WorkflowState workflowState;
  private final VariablesState variablesState;

  public BpmnEventSubscriptionBehavior(
      final BpmnStateBehavior stateBehavior,
      final BpmnStateTransitionBehavior stateTransitionBehavior,
      final CatchEventBehavior catchEventBehavior,
      final TypedStreamWriter streamWriter,
      final ZeebeState zeebeState) {
    this.stateBehavior = stateBehavior;
    this.stateTransitionBehavior = stateTransitionBehavior;
    this.catchEventBehavior = catchEventBehavior;
    this.streamWriter = streamWriter;

    workflowState = zeebeState.getWorkflowState();
    eventScopeInstanceState = workflowState.getEventScopeInstanceState();
    elementInstanceState = workflowState.getElementInstanceState();
    keyGenerator = zeebeState.getKeyGenerator();
    variablesState = elementInstanceState.getVariablesState();
  }

  public void triggerBoundaryOrIntermediateEvent(
      final ExecutableReceiveTask element, final BpmnElementContext context) {

    triggerEvent(
        context,
        eventTrigger -> {
          final boolean hasEventTriggeredForBoundaryEvent =
              element.getBoundaryEvents().stream()
                  .anyMatch(
                      boundaryEvent -> boundaryEvent.getId().equals(eventTrigger.getElementId()));

          if (hasEventTriggeredForBoundaryEvent) {
            return triggerBoundaryEvent(element, context, eventTrigger);

          } else {
            stateTransitionBehavior.transitionToCompleting(context);
            return context.getElementInstanceKey();
          }
        });
  }

  public void triggerIntermediateEvent(final BpmnElementContext context) {

    triggerEvent(
        context,
        eventTrigger -> {
          stateTransitionBehavior.transitionToCompleting(context);
          return context.getElementInstanceKey();
        });
  }

  public void triggerBoundaryEvent(
      final ExecutableActivity element, final BpmnElementContext context) {

    triggerEvent(context, eventTrigger -> triggerBoundaryEvent(element, context, eventTrigger));
  }

  private long triggerBoundaryEvent(
      final ExecutableActivity element,
      final BpmnElementContext context,
      final EventTrigger eventTrigger) {

    final var record =
        getEventRecord(context.getRecordValue(), eventTrigger, BpmnElementType.BOUNDARY_EVENT);

    final var boundaryEvent = getBoundaryEvent(element, context, eventTrigger);

    final long boundaryElementInstanceKey = keyGenerator.nextKey();
    if (boundaryEvent.interrupting()) {

      deferActivatingEvent(context, boundaryElementInstanceKey, record);

      stateTransitionBehavior.transitionToTerminating(context);

    } else {
      publishActivatingEvent(context, boundaryElementInstanceKey, record);
    }

    return boundaryElementInstanceKey;
  }

  private WorkflowInstanceRecord getEventRecord(
      final WorkflowInstanceRecord value,
      final EventTrigger event,
      final BpmnElementType elementType) {
    eventRecord.reset();
    eventRecord.wrap(value);
    eventRecord.setElementId(event.getElementId());
    eventRecord.setBpmnElementType(elementType);

    return eventRecord;
  }

  private <T extends ExecutableActivity> ExecutableBoundaryEvent getBoundaryEvent(
      final T element, final BpmnElementContext context, final EventTrigger eventTrigger) {

    return element.getBoundaryEvents().stream()
        .filter(boundaryEvent -> boundaryEvent.getId().equals(eventTrigger.getElementId()))
        .findFirst()
        .orElseThrow(
            () ->
                new BpmnProcessingException(
                    context,
                    String.format(
                        "Expected boundary event with id '%s' but not found.",
                        bufferAsString(eventTrigger.getElementId()))));
  }

  private void deferActivatingEvent(
      final BpmnElementContext context,
      final long eventElementInstanceKey,
      final WorkflowInstanceRecord record) {

    elementInstanceState.storeRecord(
        eventElementInstanceKey,
        context.getElementInstanceKey(),
        record,
        WorkflowInstanceIntent.ELEMENT_ACTIVATING,
        Purpose.DEFERRED);
  }

  public void publishTriggeredBoundaryEvent(final BpmnElementContext context) {
    publishTriggeredEvent(context, BpmnElementType.BOUNDARY_EVENT);
  }

  private void publishTriggeredEvent(
      final BpmnElementContext context, final BpmnElementType elementType) {
    elementInstanceState.getDeferredRecords(context.getElementInstanceKey()).stream()
        .filter(record -> record.getValue().getBpmnElementType() == elementType)
        .filter(record -> record.getState() == WorkflowInstanceIntent.ELEMENT_ACTIVATING)
        .findFirst()
        .ifPresent(
            deferredRecord ->
                publishActivatingEvent(
                    context, deferredRecord.getKey(), deferredRecord.getValue()));
  }

  private void publishActivatingEvent(
      final BpmnElementContext context,
      final long elementInstanceKey,
      final WorkflowInstanceRecord eventRecord) {

    streamWriter.appendNewEvent(
        elementInstanceKey, WorkflowInstanceIntent.ELEMENT_ACTIVATING, eventRecord);

    stateBehavior.createElementInstanceInFlowScope(context, elementInstanceKey, eventRecord);
    stateBehavior.spawnToken(context);
  }

  public void triggerEventBasedGateway(
      final ExecutableEventBasedGateway element, final BpmnElementContext context) {

    triggerEvent(
        context,
        eventTrigger -> {
          final var triggeredEvent = getTriggeredEvent(element, context, eventTrigger);

          final var record =
              getEventRecord(
                  context.getRecordValue(), eventTrigger, triggeredEvent.getElementType());

          final var eventElementInstanceKey = keyGenerator.nextKey();
          deferActivatingEvent(context, eventElementInstanceKey, record);

          stateTransitionBehavior.transitionToCompleting(context);

          return eventElementInstanceKey;
        });
  }

  private ExecutableFlowNode getTriggeredEvent(
      final ExecutableEventBasedGateway element,
      final BpmnElementContext context,
      final EventTrigger eventTrigger) {

    return element.getOutgoing().stream()
        .map(ExecutableSequenceFlow::getTarget)
        .filter(target -> target.getId().equals(eventTrigger.getElementId()))
        .findFirst()
        .orElseThrow(
            () ->
                new BpmnProcessingException(
                    context,
                    String.format(
                        "Expected an event attached to the event-based gateway with id '%s' but not found.",
                        bufferAsString(eventTrigger.getElementId()))));
  }

  private void triggerEvent(
      final BpmnElementContext context, final ToLongFunction<EventTrigger> eventHandler) {

    final var eventTrigger =
        eventScopeInstanceState.peekEventTrigger(context.getElementInstanceKey());

    if (eventTrigger == null) {
      // the activity (i.e. its event scope) is left - discard the event
      return;
    }

    final var eventElementInstanceKey = eventHandler.applyAsLong(eventTrigger);

    variablesState.setTemporaryVariables(eventElementInstanceKey, eventTrigger.getVariables());

    eventScopeInstanceState.deleteTrigger(
        context.getElementInstanceKey(), eventTrigger.getEventKey());
  }

  public void publishTriggeredEventBasedGateway(final BpmnElementContext context) {
    publishTriggeredEvent(context, BpmnElementType.INTERMEDIATE_CATCH_EVENT);
  }

  public void triggerStartEvent(final BpmnElementContext context) {
    final long workflowKey = context.getWorkflowKey();
    final long workflowInstanceKey = context.getWorkflowInstanceKey();

    final var workflow = workflowState.getWorkflowByKey(context.getWorkflowKey());
    if (workflow == null) {
      // this should never happen because workflows are never deleted.
      throw new BpmnProcessingException(
          context, String.format(NO_WORKFLOW_FOUND_MESSAGE, workflowKey));
    }

    final var triggeredEvent = eventScopeInstanceState.peekEventTrigger(workflowKey);
    if (triggeredEvent == null) {
      throw new BpmnProcessingException(
          context, String.format(NO_TRIGGERED_EVENT_MESSAGE, workflowKey));
    }

    createWorkflowInstance(workflow, workflowInstanceKey);

    final var record =
        getEventRecord(context.getRecordValue(), triggeredEvent, BpmnElementType.START_EVENT)
            .setWorkflowInstanceKey(workflowInstanceKey)
            .setVersion(workflow.getVersion())
            .setBpmnProcessId(workflow.getBpmnProcessId())
            .setFlowScopeKey(workflowInstanceKey);

    final var newEventInstanceKey = keyGenerator.nextKey();
    elementInstanceState.storeRecord(
        newEventInstanceKey,
        workflowInstanceKey,
        record,
        WorkflowInstanceIntent.ELEMENT_ACTIVATING,
        Purpose.DEFERRED);

    variablesState.setTemporaryVariables(newEventInstanceKey, triggeredEvent.getVariables());

    eventScopeInstanceState.deleteTrigger(workflowKey, triggeredEvent.getEventKey());
  }

  private void createWorkflowInstance(
      final DeployedWorkflow workflow, final long workflowInstanceKey) {

    recordForWFICreation
        .setBpmnProcessId(workflow.getBpmnProcessId())
        .setWorkflowKey(workflow.getKey())
        .setVersion(workflow.getVersion())
        .setWorkflowInstanceKey(workflowInstanceKey)
        .setElementId(workflow.getWorkflow().getId())
        .setBpmnElementType(workflow.getWorkflow().getElementType());

    elementInstanceState.newInstance(
        workflowInstanceKey, recordForWFICreation, WorkflowInstanceIntent.ELEMENT_ACTIVATING);

    streamWriter.appendFollowUpEvent(
        workflowInstanceKey, WorkflowInstanceIntent.ELEMENT_ACTIVATING, recordForWFICreation);
  }

  public boolean publishTriggeredStartEvent(final BpmnElementContext context) {

    final var deferredStartEvent =
        elementInstanceState.getDeferredRecords(context.getElementInstanceKey()).stream()
            .filter(record -> record.getValue().getBpmnElementType() == BpmnElementType.START_EVENT)
            .filter(record -> record.getState() == WorkflowInstanceIntent.ELEMENT_ACTIVATING)
            .findFirst();

    deferredStartEvent.ifPresent(
        deferredRecord -> {
          final var elementInstanceKey = deferredRecord.getKey();

          streamWriter.appendNewEvent(
              elementInstanceKey,
              WorkflowInstanceIntent.ELEMENT_ACTIVATING,
              deferredRecord.getValue());

          stateBehavior.createChildElementInstance(
              context, elementInstanceKey, deferredRecord.getValue());
          stateBehavior.updateElementInstance(context, ElementInstance::spawnToken);
        });

    return deferredStartEvent.isPresent();
  }

  public <T extends ExecutableCatchEventSupplier> Either<Failure, Void> subscribeToEvents(
      final T element, final BpmnElementContext context) {

    try {
      catchEventBehavior.subscribeToEvents(context.toStepContext(), element);
      return Either.right(null);

    } catch (final MessageCorrelationKeyException e) {
      return Either.left(
          new Failure(
              e.getMessage(),
              ErrorType.EXTRACT_VALUE_ERROR,
              e.getContext().getVariablesScopeKey()));

    } catch (final EvaluationException e) {
      return Either.left(
          new Failure(
              e.getMessage(), ErrorType.EXTRACT_VALUE_ERROR, context.getElementInstanceKey()));
    } catch (final MessageNameException e) {
      return Either.left(e.getFailure());
    }
  }

  public void unsubscribeFromEvents(final BpmnElementContext context) {
    catchEventBehavior.unsubscribeFromEvents(
        context.getElementInstanceKey(), context.toStepContext());
  }

  public void triggerEventSubProcess(
      final ExecutableStartEvent startEvent, final BpmnElementContext context) {

    if (stateBehavior.getFlowScopeInstance(context).getInterruptingEventKey() > 0) {
      // the flow scope is already interrupted - discard this event
      return;
    }

    final var flowScopeContext = stateBehavior.getFlowScopeContext(context);

    triggerEvent(
        flowScopeContext,
        eventTrigger -> {
          final var eventSubProcessElementId = startEvent.getEventSubProcess();
          final var record =
              getEventRecord(context.getRecordValue(), eventTrigger, BpmnElementType.SUB_PROCESS)
                  .setElementId(eventSubProcessElementId);

          final long eventElementInstanceKey = keyGenerator.nextKey();
          if (startEvent.interrupting()) {

            triggerInterruptingEventSubProcess(
                context, flowScopeContext, record, eventElementInstanceKey);

          } else {
            // activate non-interrupting event sub-process
            publishActivatingEvent(context, eventElementInstanceKey, record);
          }

          return eventElementInstanceKey;
        });
  }

  private void triggerInterruptingEventSubProcess(
      final BpmnElementContext context,
      final BpmnElementContext flowScopeContext,
      final WorkflowInstanceRecord eventRecord,
      final long eventElementInstanceKey) {

    unsubscribeFromEvents(flowScopeContext);

    final var noActiveChildInstances =
        stateTransitionBehavior.terminateChildInstances(flowScopeContext);
    if (noActiveChildInstances) {
      // activate interrupting event sub-process
      publishActivatingEvent(context, eventElementInstanceKey, eventRecord);

    } else {
      // wait until child instances are terminated
      deferActivatingEvent(flowScopeContext, eventElementInstanceKey, eventRecord);
    }

    stateBehavior.updateFlowScopeInstance(
        context,
        flowScopeInstance -> {
          flowScopeInstance.spawnToken();
          flowScopeInstance.setInterruptingEventKey(eventElementInstanceKey);
        });
  }

  public void publishTriggeredEventSubProcess(final BpmnElementContext context) {
    final var elementInstance = stateBehavior.getElementInstance(context);

    if (isInterrupted(elementInstance)) {
      elementInstanceState.getDeferredRecords(context.getElementInstanceKey()).stream()
          .filter(record -> record.getKey() == elementInstance.getInterruptingEventKey())
          .filter(record -> record.getValue().getBpmnElementType() == BpmnElementType.SUB_PROCESS)
          .findFirst()
          .ifPresent(
              record -> {
                final var elementInstanceKey = record.getKey();
                final var interruptingRecord = record.getValue();

                streamWriter.appendNewEvent(
                    elementInstanceKey,
                    WorkflowInstanceIntent.ELEMENT_ACTIVATING,
                    interruptingRecord);

                stateBehavior.createChildElementInstance(
                    context, elementInstanceKey, interruptingRecord);
              });
    }
  }

  private boolean isInterrupted(final ElementInstance elementInstance) {
    return elementInstance.getNumberOfActiveTokens() == 2
        && elementInstance.isInterrupted()
        && elementInstance.isActive();
  }
}
