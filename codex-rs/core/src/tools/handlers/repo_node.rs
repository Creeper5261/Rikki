use crate::function_tool::FunctionCallError;
use crate::tools::context::FunctionToolOutput;
use crate::tools::context::ToolInvocation;
use crate::tools::context::ToolOutput;
use crate::tools::context::ToolPayload;
use crate::tools::context::boxed_tool_output;
use crate::tools::handlers::parse_arguments;
use crate::tools::handlers::repo_node_spec::GET_REPO_NODE_TOOL_NAME;
use crate::tools::handlers::repo_node_spec::create_get_repo_node_tool;
use crate::tools::registry::CoreToolRuntime;
use crate::tools::registry::ToolExecutor;
use codex_protocol::models::ResponseInputItem;
use codex_tools::ToolName;
use codex_tools::ToolSpec;
use serde::Deserialize;
use serde_json::Value as JsonValue;
use serde_json::json;

#[derive(Debug, Deserialize)]
struct GetRepoNodeArgs {
    node_ref: String,
}

#[derive(Debug, Clone)]
struct GetRepoNodeOutput {
    value: JsonValue,
}

impl ToolOutput for GetRepoNodeOutput {
    fn log_preview(&self) -> String {
        self.value.to_string()
    }

    fn success_for_logging(&self) -> bool {
        true
    }

    fn to_response_item(&self, call_id: &str, payload: &ToolPayload) -> ResponseInputItem {
        FunctionToolOutput::from_text(self.value.to_string(), Some(true))
            .to_response_item(call_id, payload)
    }

    fn code_mode_result(&self, _payload: &ToolPayload) -> JsonValue {
        self.value.clone()
    }
}

pub struct GetRepoNodeHandler;

impl ToolExecutor<ToolInvocation> for GetRepoNodeHandler {
    fn tool_name(&self) -> ToolName {
        ToolName::plain(GET_REPO_NODE_TOOL_NAME)
    }

    fn spec(&self) -> ToolSpec {
        create_get_repo_node_tool()
    }

    fn handle(&self, invocation: ToolInvocation) -> codex_tools::ToolExecutorFuture<'_> {
        Box::pin(async move {
            let ToolPayload::Function { arguments } = &invocation.payload else {
                return Err(FunctionCallError::RespondToModel(
                    "get_repo_node handler received unsupported payload".to_string(),
                ));
            };
            let args: GetRepoNodeArgs = parse_arguments(arguments)?;
            let (_, _, _, _, task_state, _, _) = invocation
                .session
                .clone_history_with_context_governance_state()
                .await;
            let node = task_state
                .describe_navigation_node(&args.node_ref)
                .ok_or_else(|| {
                    FunctionCallError::RespondToModel(format!(
                        "repo node was not found in the current task-local graph: {}",
                        args.node_ref
                    ))
                })?;
            let value = json!({
                "node_ref": node.node_ref,
                "node_kind": node.node_kind,
                "related_paths": node.related_paths,
                "reason": node.reason,
                "tool_output_indices": node.tool_output_indices,
            });
            Ok(boxed_tool_output(GetRepoNodeOutput { value }))
        })
    }
}

impl CoreToolRuntime for GetRepoNodeHandler {}

#[cfg(test)]
#[path = "repo_node_tests.rs"]
mod tests;
