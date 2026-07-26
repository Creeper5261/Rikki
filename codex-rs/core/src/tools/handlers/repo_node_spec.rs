use std::collections::BTreeMap;

use codex_tools::JsonSchema;
use codex_tools::ResponsesApiTool;
use codex_tools::ToolSpec;
use serde_json::Value;
use serde_json::json;

pub(crate) const GET_REPO_NODE_TOOL_NAME: &str = "get_repo_node";

pub fn create_get_repo_node_tool() -> ToolSpec {
    let properties = BTreeMap::from([(
        "node_ref".to_string(),
        JsonSchema::string(Some(
            "Task-local repo-node reference emitted by the context governance manifest."
                .to_string(),
        )),
    )]);
    ToolSpec::Function(ResponsesApiTool {
        name: GET_REPO_NODE_TOOL_NAME.to_string(),
        description: "Expand a bounded task-local navigation node into its related paths and evidence references. This returns map metadata, not source code."
            .to_string(),
        strict: false,
        defer_loading: None,
        parameters: JsonSchema::object(
            properties,
            Some(vec!["node_ref".to_string()]),
            Some(false.into()),
        ),
        output_schema: Some(get_repo_node_output_schema()),
    })
}

fn get_repo_node_output_schema() -> Value {
    json!({
        "type": "object",
        "properties": {
            "node_ref": { "type": "string" },
            "node_kind": { "type": "string" },
            "related_paths": { "type": "array", "items": { "type": "string" } },
            "reason": { "type": "string" },
            "tool_output_indices": { "type": "array", "items": { "type": "integer" } }
        },
        "required": ["node_ref", "node_kind", "related_paths", "reason", "tool_output_indices"],
        "additionalProperties": false
    })
}
