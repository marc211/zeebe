/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.it.client.command;

import static io.zeebe.broker.it.util.ZeebeAssertHelper.assertVariableDocumentUpdated;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.zeebe.broker.it.util.GrpcClientRule;
import io.zeebe.broker.test.EmbeddedBrokerRule;
import io.zeebe.client.api.command.ClientException;
import io.zeebe.client.api.response.SetVariablesResponse;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.intent.VariableDocumentIntent;
import io.zeebe.protocol.record.value.VariableDocumentRecordValue;
import io.zeebe.test.util.BrokerClassRuleHelper;
import io.zeebe.test.util.record.RecordingExporter;
import java.time.Duration;
import java.util.Map;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

public final class SetVariablesTest {

  private static final String PROCESS_ID = "process";

  private static final EmbeddedBrokerRule BROKER_RULE = new EmbeddedBrokerRule();
  private static final GrpcClientRule CLIENT_RULE = new GrpcClientRule(BROKER_RULE);

  @ClassRule
  public static final RuleChain RULE_CHAIN = RuleChain.outerRule(BROKER_RULE).around(CLIENT_RULE);

  @Rule public BrokerClassRuleHelper helper = new BrokerClassRuleHelper();

  private long workflowKey;

  @Before
  public void init() {
    workflowKey =
        CLIENT_RULE.deployWorkflow(
            Bpmn.createExecutableProcess(PROCESS_ID)
                .startEvent()
                .serviceTask("task", t -> t.zeebeTaskType("test"))
                .done());
  }

  @Test
  public void shouldSetVariables() {
    // given
    final long workflowInstanceKey = CLIENT_RULE.createWorkflowInstance(workflowKey);

    // when
    final SetVariablesResponse response =
        CLIENT_RULE
            .getClient()
            .newSetVariablesCommand(workflowInstanceKey)
            .variables(Map.of("foo", "bar"))
            .send()
            .join();

    // then
    assertVariableDocumentUpdated(
        (variableDocument) ->
            assertThat(variableDocument.getVariables()).containsOnly(entry("foo", "bar")));

    final Record<VariableDocumentRecordValue> record =
        RecordingExporter.variableDocumentRecords(VariableDocumentIntent.UPDATED).getFirst();
    assertThat(response.getKey()).isEqualTo(record.getKey());
  }

  @Test
  public void shouldSetVariablesWithNullVariables() {
    // given
    final long workflowInstanceKey = CLIENT_RULE.createWorkflowInstance(workflowKey);

    // when
    CLIENT_RULE
        .getClient()
        .newSetVariablesCommand(workflowInstanceKey)
        .variables("null")
        .send()
        .join();

    // then
    assertVariableDocumentUpdated(
        (variableDocument) -> assertThat(variableDocument.getVariables()).isEmpty());
  }

  @Test
  public void shouldRejectIfVariablesAreInvalid() {
    // given
    final long workflowInstanceKey = CLIENT_RULE.createWorkflowInstance(workflowKey);

    // when
    final var command =
        CLIENT_RULE.getClient().newSetVariablesCommand(workflowInstanceKey).variables("[]").send();

    // then
    assertThatThrownBy(command::join)
        .isInstanceOf(ClientException.class)
        .hasMessageContaining(
            "Property 'variables' is invalid: Expected document to be a root level object, but was 'ARRAY'");
  }

  @Test
  public void shouldRejectIfWorkflowInstanceIsEnded() {
    // given
    final long workflowInstanceKey = CLIENT_RULE.createWorkflowInstance(workflowKey);

    CLIENT_RULE.getClient().newCancelInstanceCommand(workflowInstanceKey).send().join();

    // when
    final var command =
        CLIENT_RULE
            .getClient()
            .newSetVariablesCommand(workflowInstanceKey)
            .variables(Map.of("foo", "bar"))
            .send();

    // then
    final var expectedMessage =
        String.format(
            "Expected to update variables for element with key '%d', but no such element was found",
            workflowInstanceKey);

    assertThatThrownBy(command::join)
        .isInstanceOf(ClientException.class)
        .hasMessageContaining(expectedMessage);
  }

  @Test
  public void shouldRejectIfPartitionNotFound() {
    // given

    // when
    final int workflowInstanceKey = 0;
    final var command =
        CLIENT_RULE
            .getClient()
            .newSetVariablesCommand(workflowInstanceKey)
            .variables(Map.of("foo", "bar"))
            .requestTimeout(Duration.ofSeconds(60))
            .send();

    // then
    final String expectedMessage =
        "Expected to execute command, but this command refers to an element that doesn't exist.";
    assertThatThrownBy(command::join)
        .isInstanceOf(ClientException.class)
        .hasMessageContaining(expectedMessage)
        .hasRootCauseMessage("NOT_FOUND: " + expectedMessage);
  }
}
