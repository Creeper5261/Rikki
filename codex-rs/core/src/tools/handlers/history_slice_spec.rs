use std::collections::BTreeMap;

use codex_tools::JsonSchema;
use codex_tools::ResponsesApiTool;
use codex_tools::ToolSpec;
use serde_json::json;

pub(crate) const GET_HISTORY_SLICE_TOOL_NAME: &str = "get_history_slice";

pub fn create_get_history_slice_tool() -> ToolSpec {
    let properties = BTreeMap::from([
        (
            "index".to_string(),
            JsonSchema::number(Some("Public trajectory index.".to_string())),
        ),
        (
            "before".to_string(),
            JsonSchema::number(Some(
                "Adjacent earlier trajectory nodes, 0 through 2.".to_string(),
            )),
        ),
        (
            "after".to_string(),
            JsonSchema::number(Some(
                "Adjacent later trajectory nodes, 0 through 2.".to_string(),
            )),
        ),
        (
            "cursor".to_string(),
            JsonSchema::string(Some(
                "Continuation cursor returned by this tool.".to_string(),
            )),
        ),
        (
            "max_chars".to_string(),
            JsonSchema::number(Some("Maximum characters, 1 through 16000.".to_string())),
        ),
    ]);
    ToolSpec::Function(ResponsesApiTool {
        name: GET_HISTORY_SLICE_TOOL_NAME.to_string(),
        description: "Read a bounded raw history slice for a visible trajectory index.".to_string(),
        strict: false,
        defer_loading: None,
        parameters: JsonSchema::object(
            properties,
            Some(vec!["index".to_string()]),
            Some(false.into()),
        ),
        output_schema: Some(json!({
            "type": "object",
            "properties": {
                "index": {"type":"integer"}, "cursor": {"type":["string","null"]},
                "next_cursor": {"type":["string","null"]}, "total_chars": {"type":"integer"},
                "truncated": {"type":"boolean"}, "content": {"type":"string"}
            },
            "required": ["index", "cursor", "next_cursor", "total_chars", "truncated", "content"],
            "additionalProperties": false
        })),
    })
}
