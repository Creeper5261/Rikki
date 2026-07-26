use crate::function_tool::FunctionCallError;
use crate::tools::context::FunctionToolOutput;
use crate::tools::context::ToolInvocation;
use crate::tools::context::ToolOutput;
use crate::tools::context::ToolPayload;
use crate::tools::context::boxed_tool_output;
use crate::tools::handlers::parse_arguments;
use crate::tools::handlers::tool_output_spec::GET_TOOL_OUTPUT_TOOL_NAME;
use crate::tools::handlers::tool_output_spec::create_get_tool_output_tool;
use crate::tools::registry::CoreToolRuntime;
use crate::tools::registry::ToolExecutor;
use codex_protocol::models::ResponseInputItem;
use codex_protocol::models::ResponseItem;
use codex_tools::ToolName;
use codex_tools::ToolSpec;
use serde::Deserialize;
use serde_json::Value as JsonValue;
use serde_json::json;

const DEFAULT_MAX_CHARS: usize = 4_000;
const MIN_MAX_CHARS: usize = 1;
const HARD_MAX_CHARS: usize = 16_000;
const CURSOR_PREFIX: &str = "tool-output-v1";

#[derive(Debug, Deserialize)]
struct GetToolOutputArgs {
    index: u64,
    cursor: Option<String>,
    max_chars: Option<usize>,
}

#[derive(Debug, Clone)]
struct GetToolOutput {
    value: JsonValue,
}

impl ToolOutput for GetToolOutput {
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

pub struct GetToolOutputHandler;

impl ToolExecutor<ToolInvocation> for GetToolOutputHandler {
    fn tool_name(&self) -> ToolName {
        ToolName::plain(GET_TOOL_OUTPUT_TOOL_NAME)
    }

    fn spec(&self) -> ToolSpec {
        create_get_tool_output_tool()
    }

    fn handle(&self, invocation: ToolInvocation) -> codex_tools::ToolExecutorFuture<'_> {
        Box::pin(async move {
            let ToolPayload::Function { arguments } = &invocation.payload else {
                return Err(FunctionCallError::RespondToModel(
                    "get_tool_output handler received unsupported payload".to_string(),
                ));
            };
            let args: GetToolOutputArgs = parse_arguments(arguments)?;
            let offset = parse_cursor(args.cursor.as_deref(), args.index)?;
            let item = invocation
                .session
                .hydrate_tool_output_index(args.index)
                .await
                .ok_or_else(|| {
                    FunctionCallError::RespondToModel(format!(
                        "tool output was not found for index {}",
                        args.index
                    ))
                })?;
            let serialized = serialize_raw_tool_output(&item)?;
            let max_chars = args
                .max_chars
                .unwrap_or(DEFAULT_MAX_CHARS)
                .clamp(MIN_MAX_CHARS, HARD_MAX_CHARS);
            let (content, total_chars, next_offset) = output_chunk(&serialized, offset, max_chars);
            let next_cursor = next_offset.map(|next_offset| cursor_for(args.index, next_offset));
            let value = json!({
                "index": args.index,
                "cursor": args.cursor,
                "next_cursor": next_cursor,
                "total_chars": total_chars,
                "truncated": next_offset.is_some(),
                "content": content,
            });

            Ok(boxed_tool_output(GetToolOutput { value }))
        })
    }
}

impl CoreToolRuntime for GetToolOutputHandler {}

fn serialize_raw_tool_output(item: &ResponseItem) -> Result<String, FunctionCallError> {
    let mut value = serde_json::to_value(item).map_err(|err| {
        FunctionCallError::RespondToModel(format!("failed to serialize tool output: {err}"))
    })?;
    let JsonValue::Object(object) = &mut value else {
        return Err(FunctionCallError::RespondToModel(
            "stored record is not a tool output".to_string(),
        ));
    };
    object.remove("id");
    object.remove("call_id");
    object.remove("internal_chat_message_metadata_passthrough");
    serde_json::to_string_pretty(&value).map_err(|err| {
        FunctionCallError::RespondToModel(format!("failed to render tool output: {err}"))
    })
}

fn parse_cursor(cursor: Option<&str>, index: u64) -> Result<usize, FunctionCallError> {
    let Some(cursor) = cursor else {
        return Ok(0);
    };
    let mut parts = cursor.split(':');
    let valid = matches!(parts.next(), Some(CURSOR_PREFIX))
        && parts.next().and_then(|value| value.parse::<u64>().ok()) == Some(index);
    let offset = parts.next().and_then(|value| value.parse::<usize>().ok());
    if valid && offset.is_some() && parts.next().is_none() {
        return Ok(offset.expect("checked is_some"));
    }
    Err(FunctionCallError::RespondToModel(
        "cursor is invalid for the requested tool output index".to_string(),
    ))
}

fn cursor_for(index: u64, offset: usize) -> String {
    format!("{CURSOR_PREFIX}:{index}:{offset}")
}

fn output_chunk(text: &str, offset: usize, max_chars: usize) -> (String, usize, Option<usize>) {
    let total_chars = text.chars().count();
    let offset = offset.min(total_chars);
    let content = text
        .chars()
        .skip(offset)
        .take(max_chars)
        .collect::<String>();
    let consumed = content.chars().count();
    let next_offset = (offset + consumed < total_chars).then_some(offset + consumed);
    (content, total_chars, next_offset)
}

#[cfg(test)]
#[path = "tool_output_tests.rs"]
mod tests;
