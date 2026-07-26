use crate::function_tool::FunctionCallError;
use crate::tools::context::FunctionToolOutput;
use crate::tools::context::ToolInvocation;
use crate::tools::context::ToolOutput;
use crate::tools::context::ToolPayload;
use crate::tools::context::boxed_tool_output;
use crate::tools::handlers::history_slice_spec::GET_HISTORY_SLICE_TOOL_NAME;
use crate::tools::handlers::history_slice_spec::create_get_history_slice_tool;
use crate::tools::handlers::parse_arguments;
use crate::tools::registry::CoreToolRuntime;
use crate::tools::registry::ToolExecutor;
use codex_protocol::models::ResponseInputItem;
use codex_tools::ToolName;
use codex_tools::ToolSpec;
use serde::Deserialize;
use serde_json::Value as JsonValue;
use serde_json::json;

const DEFAULT_MAX_CHARS: usize = 4_000;
const HARD_MAX_CHARS: usize = 16_000;
const CURSOR_PREFIX: &str = "history-slice-v1";

#[derive(Debug, Deserialize)]
struct GetHistorySliceArgs {
    index: u64,
    before: Option<usize>,
    after: Option<usize>,
    cursor: Option<String>,
    max_chars: Option<usize>,
}

#[derive(Clone)]
struct GetHistorySlice {
    value: JsonValue,
}
impl ToolOutput for GetHistorySlice {
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

pub struct GetHistorySliceHandler;
impl ToolExecutor<ToolInvocation> for GetHistorySliceHandler {
    fn tool_name(&self) -> ToolName {
        ToolName::plain(GET_HISTORY_SLICE_TOOL_NAME)
    }
    fn spec(&self) -> ToolSpec {
        create_get_history_slice_tool()
    }
    fn handle(&self, invocation: ToolInvocation) -> codex_tools::ToolExecutorFuture<'_> {
        Box::pin(async move {
            let ToolPayload::Function { arguments } = &invocation.payload else {
                return Err(FunctionCallError::RespondToModel(
                    "get_history_slice handler received unsupported payload".to_string(),
                ));
            };
            let args: GetHistorySliceArgs = parse_arguments(arguments)?;
            let offset = parse_cursor(args.cursor.as_deref(), args.index)?;
            let max_chars = args
                .max_chars
                .unwrap_or(DEFAULT_MAX_CHARS)
                .clamp(1, HARD_MAX_CHARS);
            let page = invocation
                .session
                .hydrate_history_slice_page(
                    args.index,
                    args.before.unwrap_or(0).min(2),
                    args.after.unwrap_or(0).min(2),
                    offset,
                    max_chars,
                )
                .await
                .map_err(|_| {
                    FunctionCallError::RespondToModel(format!(
                        "history slice was not found for index {}",
                        args.index
                    ))
                })?;
            let value = json!({"index": page.index, "cursor": args.cursor, "next_cursor": page.next_offset.map(|next| cursor_for(args.index, next)), "total_chars": page.total_chars, "truncated": page.truncated, "content": page.content});
            Ok(boxed_tool_output(GetHistorySlice { value }))
        })
    }
}
impl CoreToolRuntime for GetHistorySliceHandler {}

fn parse_cursor(cursor: Option<&str>, index: u64) -> Result<usize, FunctionCallError> {
    let Some(cursor) = cursor else {
        return Ok(0);
    };
    let mut parts = cursor.split(':');
    let valid = matches!(parts.next(), Some(CURSOR_PREFIX))
        && parts.next().and_then(|part| part.parse::<u64>().ok()) == Some(index);
    let offset = parts.next().and_then(|part| part.parse::<usize>().ok());
    if valid && offset.is_some() && parts.next().is_none() {
        Ok(offset.expect("checked"))
    } else {
        Err(FunctionCallError::RespondToModel(
            "cursor is invalid for the requested history slice index".to_string(),
        ))
    }
}
fn cursor_for(index: u64, offset: usize) -> String {
    format!("{CURSOR_PREFIX}:{index}:{offset}")
}

#[cfg(test)]
#[path = "history_slice_tests.rs"]
mod tests;
