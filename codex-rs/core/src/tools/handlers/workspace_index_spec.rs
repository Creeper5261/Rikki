use std::collections::BTreeMap;

use codex_tools::JsonSchema;
use codex_tools::ResponsesApiTool;
use codex_tools::ToolSpec;
use serde_json::Value;
use serde_json::json;

pub(crate) const SEARCH_WORKSPACE_FILES_TOOL_NAME: &str = "search_workspace_files";

pub fn create_search_workspace_files_tool() -> ToolSpec {
    let properties = BTreeMap::from([
        (
            "query".to_string(),
            JsonSchema::string(Some(
                "Case-insensitive filename or relative-path query.".to_string(),
            )),
        ),
        (
            "max_results".to_string(),
            JsonSchema::number(Some("Maximum paths to return, capped at 64.".to_string())),
        ),
    ]);
    ToolSpec::Function(ResponsesApiTool {
        name: SEARCH_WORKSPACE_FILES_TOOL_NAME.to_string(),
        description: "Search a bounded index of workspace file paths. Returns paths only, never file contents or inferred dependency relationships."
            .to_string(),
        strict: false,
        defer_loading: None,
        parameters: JsonSchema::object(
            properties,
            Some(vec!["query".to_string()]),
            Some(false.into()),
        ),
        output_schema: Some(workspace_search_output_schema()),
    })
}

fn workspace_search_output_schema() -> Value {
    json!({
        "type": "object",
        "properties": {
            "root": { "type": "string" },
            "query": { "type": "string" },
            "matches": { "type": "array", "items": { "type": "string" } },
            "scanned_entries": { "type": "integer" },
            "truncated": { "type": "boolean" },
            "index_kind": { "type": "string" },
            "content_included": { "type": "boolean" }
        },
        "required": ["root", "query", "matches", "scanned_entries", "truncated", "index_kind", "content_included"],
        "additionalProperties": false
    })
}
