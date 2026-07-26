use super::*;
use crate::session::step_context::StepContext;
use crate::session::tests::make_session_and_context;
use crate::tools::context::ToolCallSource;
use crate::turn_diff_tracker::TurnDiffTracker;
use codex_protocol::models::FunctionCallOutputPayload;
use codex_protocol::models::ResponseItem;
use serde_json::json;
use std::sync::Arc;
use tokio::sync::Mutex;

#[tokio::test]
async fn handler_expands_task_local_navigation_node_without_source_content() {
    let (session, turn) = make_session_and_context().await;
    let test_call = ResponseItem::FunctionCall {
        id: Some("node-test-call".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: json!({"command": "pytest -q", "workdir": "D:/repo"}).to_string(),
        call_id: "node-test".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let failed_test = ResponseItem::FunctionCallOutput {
        id: Some("node-test-output".to_string()),
        call_id: "node-test".to_string(),
        output: FunctionCallOutputPayload {
            body: codex_protocol::models::FunctionCallOutputBody::Text(
                "FAILED tests/test_lib.py::test_behavior - expected value\n".to_string(),
            ),
            success: Some(false),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let diff_call = ResponseItem::FunctionCall {
        id: Some("node-diff-call".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: json!({"command": "git diff --name-only", "workdir": "D:/repo"}).to_string(),
        call_id: "node-diff".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let diff_output = ResponseItem::FunctionCallOutput {
        id: Some("node-diff-output".to_string()),
        call_id: "node-diff".to_string(),
        output: FunctionCallOutputPayload {
            body: codex_protocol::models::FunctionCallOutputBody::Text("src/lib.py\n".to_string()),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    session
        .record_conversation_items(&turn, &[test_call, failed_test, diff_call, diff_output])
        .await;
    let session = Arc::new(session);
    let turn = Arc::new(turn);
    let payload = ToolPayload::Function {
        arguments: json!({"node_ref": "repo-node://tests/test_lib.py"}).to_string(),
    };

    let output = GetRepoNodeHandler
        .handle(ToolInvocation {
            session,
            step_context: StepContext::for_test(Arc::clone(&turn)),
            turn,
            cancellation_token: tokio_util::sync::CancellationToken::new(),
            tracker: Arc::new(Mutex::new(TurnDiffTracker::default())),
            call_id: "get-node".to_string(),
            tool_name: ToolName::plain(GET_REPO_NODE_TOOL_NAME),
            source: ToolCallSource::Direct,
            payload: payload.clone(),
        })
        .await
        .expect("repo node tool should expand current graph node");
    let result = output.code_mode_result(&payload);

    assert_eq!(result["node_kind"], "test");
    assert_eq!(result["related_paths"], json!(["src/lib.py"]));
    assert_eq!(
        result["tool_output_indices"].as_array().map(Vec::len),
        Some(2)
    );
    assert!(result.get("content").is_none());
}
