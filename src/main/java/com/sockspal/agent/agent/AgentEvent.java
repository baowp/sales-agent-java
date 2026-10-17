package com.sockspal.agent.agent;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import tools.jackson.databind.JsonNode;

/**
 * Progress the loop reports while it works. The web UI receives them as NDJSON lines such as
 * {@code {"type":"tool","name":"calculate_quote","args":{...}}}; the CLI prints them.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(AgentEvent.ToolCall.class),
        @JsonSubTypes.Type(AgentEvent.ToolError.class),
        @JsonSubTypes.Type(AgentEvent.RfqSaved.class),
        @JsonSubTypes.Type(AgentEvent.Retry.class)})
public sealed interface AgentEvent {

    /** The model asked for a tool; it is about to run. */
    @JsonTypeName("tool")
    record ToolCall(String name, JsonNode args) implements AgentEvent {}

    /** A tool failed. The error went back to the model as the tool result. */
    @JsonTypeName("tool_error")
    record ToolError(String name, String message) implements AgentEvent {}

    @JsonTypeName("rfq")
    record RfqSaved(String id, String status) implements AgentEvent {}

    /** The LLM call failed with 429/5xx ({@code status} is the code) or timed out ({@code "timeout"}). */
    @JsonTypeName("retry")
    record Retry(String status, long seconds) implements AgentEvent {}
}
