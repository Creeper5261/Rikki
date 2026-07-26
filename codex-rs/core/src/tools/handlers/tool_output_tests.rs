use super::*;
use crate::session::step_context::StepContext;
use crate::session::tests::make_session_and_context;
use crate::tools::context::ToolCallSource;
use crate::turn_diff_tracker::TurnDiffTracker;
use codex_protocol::models::FunctionCallOutputBody;
use codex_protocol::models::FunctionCallOutputPayload;
use pretty_assertions::assert_eq;
use serde_json::json;
use std::sync::Arc;
use tokio::sync::Mutex;

#[test]
fn output_chunk_uses_character_offsets_and_reports_continuation() {
    assert_eq!(
        output_chunk("ab中文cd", 2, 2),
        ("中文".to_string(), 6, Some(4))
    );
    assert_eq!(output_chunk("abc", 99, 20), (String::new(), 3, None));
}

#[test]
fn cursor_rejects_cross_record_reuse() {
    assert_eq!(parse_cursor(Some("tool-output-v1:4:10"), 4), Ok(10));
    assert!(parse_cursor(Some("tool-output-v1:4:10"), 5).is_err());
}

#[tokio::test]
async fn handler_hydrates_tool_output_without_internal_identifiers() {
    let (session, turn) = make_session_and_context().await;
    let item = codex_protocol::models::ResponseItem::FunctionCallOutput {
        id: Some("output-hidden-id".to_string()),
        call_id: "call-hidden-id".to_string(),
        output: FunctionCallOutputPayload {
            body: FunctionCallOutputBody::Text("exact tool output".to_string()),
            success: Some(false),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    session
        .record_conversation_items(&turn, std::slice::from_ref(&item))
        .await;
    assert!(session.hydrate_tool_output_index(0).await.is_none());
    session.mark_pending_tool_outputs_observed().await;
    let session = Arc::new(session);
    let turn = Arc::new(turn);
    let payload = ToolPayload::Function {
        arguments: json!({ "index": 0, "max_chars": 1_000 }).to_string(),
    };

    let output = GetToolOutputHandler
        .handle(ToolInvocation {
            session,
            step_context: StepContext::for_test(Arc::clone(&turn)),
            turn,
            cancellation_token: tokio_util::sync::CancellationToken::new(),
            tracker: Arc::new(Mutex::new(TurnDiffTracker::default())),
            call_id: "call-hydrate".to_string(),
            tool_name: ToolName::plain(GET_TOOL_OUTPUT_TOOL_NAME),
            source: ToolCallSource::Direct,
            payload: payload.clone(),
        })
        .await
        .expect("tool output should hydrate");
    let result = output.code_mode_result(&payload);
    let content = result["content"].as_str().expect("content should be text");

    assert_eq!(result["index"], 0);
    assert_eq!(result["truncated"], false);
    assert!(content.contains("exact tool output"));
    assert!(!content.contains("output-hidden-id"));
    assert!(!content.contains("call-hidden-id"));
}
