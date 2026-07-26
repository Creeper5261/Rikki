use std::collections::BTreeMap;

use codex_tools::JsonSchema;
use codex_tools::ResponsesApiTool;
use codex_tools::ToolSpec;
use serde_json::Value;
use serde_json::json;

pub(crate) const GET_TOOL_OUTPUT_TOOL_NAME: &str = "get_tool_output";

pub fn create_get_tool_output_tool() -> ToolSpec {
    let properties = BTreeMap::from([
        (
            "index".to_string(),
            JsonSchema::number(Some(
                "Public index shown by a compacted tool result.".to_string(),
            )),
        ),
        (
            "cursor".to_string(),
            JsonSchema::string(Some(
                "Continuation cursor returned by an earlier get_tool_output call.".to_string(),
            )),
        ),
        (
            "max_chars".to_string(),
            JsonSchema::number(Some(
                "Maximum characters to return. Defaults to 4000 and is capped at 16000."
                    .to_string(),
            )),
        ),
    ]);

    ToolSpec::Function(ResponsesApiTool {
        name: GET_TOOL_OUTPUT_TOOL_NAME.to_string(),
        description: "Read the complete original output of a prior tool call by its public index. Use this only when the compacted tool result lacks a necessary exact detail."
            .to_string(),
        strict: false,
        defer_loading: None,
        parameters: JsonSchema::object(
            properties,
            Some(vec!["index".to_string()]),
            Some(false.into()),
        ),
        output_schema: Some(get_tool_output_output_schema()),
    })
}

fn get_tool_output_output_schema() -> Value {
    json!({
        "type": "object",
        "properties": {
            "index": { "type": "integer" },
            "cursor": { "type": ["string", "null"] },
            "next_cursor": { "type": ["string", "null"] },
            "total_chars": { "type": "integer" },
            "truncated": { "type": "boolean" },
            "content": { "type": "string" }
        },
        "required": [
            "index",
            "cursor",
            "next_cursor",
            "total_chars",
            "truncated",
            "content"
        ],
        "additionalProperties": false
    })
}
