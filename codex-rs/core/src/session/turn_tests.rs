use super::*;
use codex_extension_api::ExtensionData;
use codex_extension_api::TurnItemContributor;
use codex_protocol::items::AgentMessageContent;
use codex_protocol::models::FunctionCallOutputBody;
use codex_protocol::models::FunctionCallOutputPayload;
use pretty_assertions::assert_eq;
use std::sync::Arc;

struct RewriteAgentMessageContributor;

impl TurnItemContributor for RewriteAgentMessageContributor {
    fn contribute<'a>(
        &'a self,
        _thread_store: &'a ExtensionData,
        _turn_store: &'a ExtensionData,
        item: &'a mut TurnItem,
    ) -> codex_extension_api::ExtensionFuture<'a, Result<(), String>> {
        Box::pin(async move {
            if let TurnItem::AgentMessage(agent_message) = item {
                agent_message.content = vec![AgentMessageContent::Text {
                    text: "plan contributed assistant text".to_string(),
                }];
            }
            Ok(())
        })
    }
}

fn assistant_output_text(text: &str) -> ResponseItem {
    ResponseItem::Message {
        id: Some("msg-1".to_string()),
        role: "assistant".to_string(),
        content: vec![ContentItem::OutputText {
            text: text.to_string(),
        }],
        phase: None,
        internal_chat_message_metadata_passthrough: None,
    }
}

fn tool_output_text(text: &str) -> ResponseItem {
    ResponseItem::FunctionCallOutput {
        id: Some("tool-output-1".to_string()),
        call_id: "tool-call-1".to_string(),
        output: FunctionCallOutputPayload {
            body: FunctionCallOutputBody::Text(text.to_string()),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    }
}

fn user_input_text(text: &str) -> ResponseItem {
    ResponseItem::Message {
        id: None,
        role: "user".to_string(),
        content: vec![ContentItem::InputText {
            text: text.to_string(),
        }],
        phase: None,
        internal_chat_message_metadata_passthrough: None,
    }
}

fn joined_input_text(items: &[ResponseItem]) -> String {
    let mut text = Vec::new();

    for item in items {
        if let ResponseItem::Message { content, .. } = item {
            for content_item in content {
                if let ContentItem::InputText { text: item_text }
                | ContentItem::OutputText { text: item_text } = content_item
                {
                    text.push(item_text.as_str());
                }
            }
        }
    }

    text.join("\n")
}

#[test]
fn governed_sampling_input_keeps_current_user_input_and_drops_over_budget_history() {
    let large_history = user_input_text(&format!(
        "OLD_TOOL_OUTPUT_SHOULD_NOT_REACH_MODEL {}",
        "x".repeat(2_000)
    ));
    let current_user = user_input_text("Continue the runtime integration.");

    let governed = assemble_governed_model_context(
        vec![large_history, current_user],
        crate::context_engine::ContextBudget {
            max_tokens: 80,
            min_density: 0.01,
        },
    )
    .expect("governed sampling input should assemble");

    let model_text = joined_input_text(&governed.model_input);
    assert!(model_text.contains("CONTEXT GOVERNANCE NOTE"));
    assert!(model_text.contains("Continue the runtime integration."));
    assert!(!model_text.contains("OLD_TOOL_OUTPUT_SHOULD_NOT_REACH_MODEL"));
    assert_eq!(governed.manifest.dropped_ids(), vec!["history-0"]);
}

#[test]
fn governed_sampling_manifest_prefers_evidence_refs_for_recorded_history() {
    let evidence_refs = HashMap::from([(
        "msg-1".to_string(),
        "evidence://response-item/msg-1?record=7".to_string(),
    )]);

    let governed = assemble_governed_model_context_with_evidence_refs(
        vec![
            assistant_output_text("Previously verified implementation detail."),
            user_input_text("Continue."),
        ],
        crate::context_engine::ContextBudget {
            max_tokens: 1_000,
            min_density: 0.0,
        },
        &evidence_refs,
        &HashMap::new(),
        &HashSet::new(),
        /*task_state*/ None,
        None,
        &[],
    )
    .expect("governed sampling input should assemble");

    assert_eq!(
        governed.manifest.selected[0].source_ref,
        "evidence://response-item/msg-1?record=7"
    );
}

#[test]
fn governed_sampling_input_fails_closed_without_current_user_input() {
    let err = assemble_governed_model_context(
        vec![assistant_output_text("No user instruction exists.")],
        crate::context_engine::ContextBudget {
            max_tokens: 80,
            min_density: 0.01,
        },
    )
    .expect_err("governed sampling must not fall back to raw history");

    assert!(matches!(
        err,
        crate::context_engine::ContextGovernanceError::CurrentUserInputMissing
    ));
}

#[tokio::test]
async fn session_hydrates_recorded_tool_output_by_public_index() {
    let (session, turn_context) = crate::session::tests::make_session_and_context().await;
    let item = tool_output_text("Tool payload available after context selection.");
    session
        .record_conversation_items(&turn_context, std::slice::from_ref(&item))
        .await;
    session.mark_pending_tool_outputs_observed().await;

    let hydrated = session
        .hydrate_tool_output_index(0)
        .await
        .expect("recorded tool output should be recoverable");

    assert_eq!(hydrated.id(), Some("tool-output-1"));
    assert!(
        serde_json::to_string(&hydrated)
            .expect("tool output should serialize")
            .contains("Tool payload available after context selection.")
    );
}

#[tokio::test]
async fn session_hydrates_tool_output_after_history_replacement() {
    let (mut session, turn_context) = crate::session::tests::make_session_and_context().await;
    crate::session::tests::attach_thread_persistence(&mut session).await;
    let item = tool_output_text("Tool output survives history replacement.");
    session
        .record_conversation_items(&turn_context, std::slice::from_ref(&item))
        .await;
    session.mark_pending_tool_outputs_observed().await;
    session
        .state
        .lock()
        .await
        .replace_history(Vec::new(), /*reference_context_item*/ None);

    let hydrated = session
        .hydrate_tool_output_index(0)
        .await
        .expect("tool output should survive history replacement");

    assert_eq!(hydrated.id(), Some("tool-output-1"));
    assert!(
        serde_json::to_string(&hydrated)
            .expect("tool output should serialize")
            .contains("Tool output survives history replacement.")
    );
}

#[tokio::test]
async fn governed_context_replays_observed_tool_pair_as_fact_only_note() {
    let (session, turn_context) = crate::session::tests::make_session_and_context().await;
    let call = ResponseItem::FunctionCall {
        id: Some("note-call-item".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({"command": "pytest -q"}).to_string(),
        call_id: "note-call".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let output = ResponseItem::FunctionCallOutput {
        id: Some("note-output-item".to_string()),
        call_id: "note-call".to_string(),
        output: FunctionCallOutputPayload {
            body: FunctionCallOutputBody::Text("3 passed in 0.04s".to_string()),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    session
        .record_conversation_items(
            &turn_context,
            &[call.clone(), output.clone(), user_input_text("Continue.")],
        )
        .await;

    let before_completion = build_governed_model_context_from_session(&session, &turn_context)
        .await
        .expect("unobserved output must remain in its original protocol form");
    assert!(before_completion.model_input.iter().any(
        |item| matches!(item, ResponseItem::FunctionCall { call_id, .. } if call_id == "note-call")
    ));
    assert!(before_completion.model_input.iter().any(
        |item| matches!(item, ResponseItem::FunctionCallOutput { call_id, .. } if call_id == "note-call")
    ));

    session.mark_pending_tool_outputs_observed().await;
    let after_completion = build_governed_model_context_from_session(&session, &turn_context)
        .await
        .expect("observed output should have a recoverable note projection");
    let model_text = joined_input_text(&after_completion.model_input);

    assert!(!after_completion.model_input.iter().any(
        |item| matches!(item, ResponseItem::FunctionCall { call_id, .. } if call_id == "note-call")
    ));
    assert!(!after_completion.model_input.iter().any(
        |item| matches!(item, ResponseItem::FunctionCallOutput { call_id, .. } if call_id == "note-call")
    ));
    assert!(model_text.contains("tool: pytest -q"));
    assert!(model_text.contains("status: succeeded"));
    assert!(model_text.contains("summary: 3 passed in 0.04s"));
    assert!(model_text.contains("raw: get_tool_output(index=1)"));
    assert!(!model_text.contains("note-output-item"));
}

#[tokio::test]
async fn governed_context_projects_completed_recovery_result_as_bounded_fact() {
    let (session, turn_context) = crate::session::tests::make_session_and_context().await;
    let call = ResponseItem::FunctionCall {
        id: Some("recovery-call-item".to_string()),
        name: "get_tool_output".to_string(),
        namespace: None,
        arguments: serde_json::json!({"index": 1889}).to_string(),
        call_id: "recovery-call".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let output = ResponseItem::FunctionCallOutput {
        id: Some("recovery-output-item".to_string()),
        call_id: "recovery-call".to_string(),
        output: FunctionCallOutputPayload {
            body: FunctionCallOutputBody::Text(
                serde_json::json!({
                    "index": 1889,
                    "cursor": null,
                    "next_cursor": null,
                    "total_chars": 13,
                    "truncated": false,
                    "content": "RIKKI_PLUS_OK"
                })
                .to_string(),
            ),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    session
        .record_conversation_items(&turn_context, &[call, output, user_input_text("Continue.")])
        .await;
    session.mark_pending_tool_outputs_observed().await;

    let governed = build_governed_model_context_from_session(&session, &turn_context)
        .await
        .expect("completed recovery output should remain a model-visible fact");
    let model_text = joined_input_text(&governed.model_input);

    assert!(model_text.contains("recovery_tool: get_tool_output"));
    assert!(model_text.contains("status: succeeded"));
    assert!(model_text.contains("index: 1889"));
    assert!(model_text.contains("content_excerpt: RIKKI_PLUS_OK"));
    assert!(!governed.model_input.iter().any(
        |item| matches!(item, ResponseItem::FunctionCall { call_id, .. } if call_id == "recovery-call")
    ));
}

#[tokio::test]
async fn governed_context_note_keeps_verbatim_failure_diagnostic_without_advice() {
    let (session, turn_context) = crate::session::tests::make_session_and_context().await;
    let call = ResponseItem::FunctionCall {
        id: Some("failure-call-item".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({"command": "pytest tests/test_validation.py"}).to_string(),
        call_id: "failure-call".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let output = ResponseItem::FunctionCallOutput {
        id: Some("failure-output-item".to_string()),
        call_id: "failure-call".to_string(),
        output: FunctionCallOutputPayload {
            body: FunctionCallOutputBody::Text(
                "ERROR collecting tests/test_validation.py\nModuleNotFoundError: No module named 'mock'\ncollected 0 items"
                    .to_string(),
            ),
            success: Some(false),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    session
        .record_conversation_items(&turn_context, &[call, output, user_input_text("Continue.")])
        .await;
    session.mark_pending_tool_outputs_observed().await;

    let governed = build_governed_model_context_from_session(&session, &turn_context)
        .await
        .expect("failure note should be projected");
    let model_text = joined_input_text(&governed.model_input);

    assert!(model_text.contains("status: failed"));
    assert!(model_text.contains("diagnostic: ERROR collecting tests/test_validation.py"));
    assert!(model_text.contains("ModuleNotFoundError: No module named 'mock'"));
    assert!(model_text.contains("summary: collected 0 items"));
    assert!(!model_text.contains("should fix"));
}

#[tokio::test]
async fn governed_context_keeps_observed_file_reads_in_original_protocol_form() {
    let (session, turn_context) = crate::session::tests::make_session_and_context().await;
    let call = ResponseItem::FunctionCall {
        id: Some("read-call-item".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({"command": "Get-Content src/lib.py"}).to_string(),
        call_id: "read-call".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let output = ResponseItem::FunctionCallOutput {
        id: Some("read-output-item".to_string()),
        call_id: "read-call".to_string(),
        output: FunctionCallOutputPayload {
            body: FunctionCallOutputBody::Text("SOURCE_CONTENT_SHOULD_REMAIN".to_string()),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    session
        .record_conversation_items(&turn_context, &[call, output, user_input_text("Continue.")])
        .await;
    session.mark_pending_tool_outputs_observed().await;

    let governed = build_governed_model_context_from_session(&session, &turn_context)
        .await
        .expect("read pair should remain available");
    assert!(governed.model_input.iter().any(
        |item| matches!(item, ResponseItem::FunctionCall { call_id, .. } if call_id == "read-call")
    ));
    assert!(governed.model_input.iter().any(
        |item| matches!(item, ResponseItem::FunctionCallOutput { call_id, .. } if call_id == "read-call")
    ));
    assert!(
        serde_json::to_string(&governed.model_input)
            .expect("model input should serialize")
            .contains("SOURCE_CONTENT_SHOULD_REMAIN")
    );
}

#[tokio::test]
async fn governed_context_projects_persisted_trajectory_and_recovery_indices() {
    let (session, turn_context) = crate::session::tests::make_session_and_context().await;
    session
        .record_conversation_items(
            &turn_context,
            &[
                user_input_text("Original long-running request."),
                user_input_text("Current request."),
            ],
        )
        .await;
    session
        .append_context_trajectory_node("user narrowed the request".to_string(), 0, 1, 0, 1, 1)
        .await
        .expect("trajectory node should be valid");
    session
        .set_context_governance_projection(crate::context_engine::GovernanceProjection {
            user_intent: "{\"continuing_context\":\"long-running implementation\"}".to_string(),
            current_position: "implementation is in progress".to_string(),
            recent_episode: "the user narrowed the request".to_string(),
        })
        .await;

    let governed = build_governed_model_context_from_session(&session, &turn_context)
        .await
        .expect("governance state should project with current user input");
    let text = joined_input_text(&governed.model_input);
    assert!(text.contains("USER INTENT"));
    assert!(text.contains("CURRENT POSITION"));
    assert!(text.contains("TRAJECTORY MAP"));
    assert!(text.contains("[0] user narrowed the request"));
    assert!(text.contains("RECENT EPISODE"));
    assert!(!text.contains("hidden_id"));
}

#[tokio::test]
async fn governed_session_context_injects_bounded_task_state_snapshot() {
    let (session, turn_context) = crate::session::tests::make_session_and_context().await;
    session
        .record_conversation_items(
            &turn_context,
            &[
                user_input_text("Original requirement."),
                user_input_text("Current requirement revision."),
            ],
        )
        .await;

    let governed = build_governed_model_context_from_session(&session, &turn_context)
        .await
        .expect("governed session context should assemble");
    let model_text = joined_input_text(&governed.model_input);

    assert!(model_text.contains("TASK STATE SNAPSHOT"));
    assert!(model_text.contains("requirements: revision_count=2"));
    assert!(model_text.contains("Current requirement revision."));
    assert!(governed.manifest.task_state_tokens > 0);
    assert!(governed.manifest.governance_note_tokens > 0);
    assert!(
        governed.manifest.total_selected_tokens <= governed.manifest.budget.max_tokens,
        "task state and governance note tokens should be accounted inside the governance budget"
    );
}

#[tokio::test]
async fn governed_task_state_projects_tool_scope_freshness_and_resolution() {
    let (session, turn_context) = crate::session::tests::make_session_and_context().await;
    let call = |item_id: &str, call_id: &str| ResponseItem::FunctionCall {
        id: Some(item_id.to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({
            "command": "pytest -q",
            "workdir": "D:/repo/tests"
        })
        .to_string(),
        call_id: call_id.to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let output = |item_id: &str, call_id: &str, success: bool| ResponseItem::FunctionCallOutput {
        id: Some(item_id.to_string()),
        call_id: call_id.to_string(),
        output: FunctionCallOutputPayload {
            body: Default::default(),
            success: Some(success),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    session
        .record_conversation_items(
            &turn_context,
            &[
                user_input_text("Fix the failing tests."),
                call("call-item-1", "call-test-1"),
                output("output-item-1", "call-test-1", false),
                call("call-item-2", "call-test-2"),
                output("output-item-2", "call-test-2", true),
                user_input_text("Continue after verification."),
            ],
        )
        .await;

    let governed = build_governed_model_context_from_session(&session, &turn_context)
        .await
        .expect("governed session context should assemble");
    let model_text = joined_input_text(&governed.model_input);

    assert!(model_text.contains("unresolved_failed_count=0"));
    assert!(model_text.contains("category=test"));
    assert!(model_text.contains("scope=D:/repo/tests"));
    assert!(model_text.contains("resolution=superseded_by:"));
    assert!(model_text.contains(&format!("turn_id={}", turn_context.sub_id)));
}

#[tokio::test]
async fn governed_task_state_projects_closed_plan_subgoal_with_verifier_reference() {
    let (session, turn_context) = crate::session::tests::make_session_and_context().await;
    let plan = |item_id: &str, status: &str| ResponseItem::FunctionCall {
        id: Some(item_id.to_string()),
        name: "update_plan".to_string(),
        namespace: None,
        arguments: serde_json::json!({
            "plan": [{
                "step": "Implement closure gate",
                "status": status,
                "verification": {"command": "pytest -q", "scope": ["D:/repo"]}
            }]
        })
        .to_string(),
        call_id: format!("{item_id}-call"),
        internal_chat_message_metadata_passthrough: None,
    };
    let test_call = ResponseItem::FunctionCall {
        id: Some("closure-verify-call".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({"command": "pytest -q", "workdir": "D:/repo"}).to_string(),
        call_id: "closure-verify".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let test_output = ResponseItem::FunctionCallOutput {
        id: Some("closure-verify-output".to_string()),
        call_id: "closure-verify".to_string(),
        output: FunctionCallOutputPayload {
            body: Default::default(),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    session
        .record_conversation_items(
            &turn_context,
            &[
                user_input_text("Implement the closure gate."),
                plan("closure-plan-active", "in_progress"),
                test_call,
                test_output,
                plan("closure-plan-complete", "completed"),
                user_input_text("Continue with the verified state."),
            ],
        )
        .await;

    let governed = build_governed_model_context_from_session(&session, &turn_context)
        .await
        .expect("governed session context should assemble");
    let model_text = joined_input_text(&governed.model_input);

    assert!(model_text.contains("subgoals: total_count=1"));
    assert!(model_text.contains("closed_count=1"));
    assert!(model_text.contains("status=closed"));
    assert!(model_text.contains("verifier_command=pytest -q"));
    assert!(model_text.contains("verifier_tool_output_index="));
}

#[tokio::test]
async fn governed_task_state_reopens_closed_subgoal_after_dirty_worktree_snapshot() {
    let (session, turn_context) = crate::session::tests::make_session_and_context().await;
    let plan = |item_id: &str, status: &str| ResponseItem::FunctionCall {
        id: Some(item_id.to_string()),
        name: "update_plan".to_string(),
        namespace: None,
        arguments: serde_json::json!({
            "plan": [{
                "step": "Verify implementation",
                "status": status,
                "verification": {"command": "pytest -q", "scope": ["D:/repo"]}
            }]
        })
        .to_string(),
        call_id: format!("{item_id}-call"),
        internal_chat_message_metadata_passthrough: None,
    };
    let shell_call = |item_id: &str, call_id: &str, command: &str| ResponseItem::FunctionCall {
        id: Some(item_id.to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({"command": command, "workdir": "D:/repo"}).to_string(),
        call_id: call_id.to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let succeeded = |item_id: &str, call_id: &str, text: &str| ResponseItem::FunctionCallOutput {
        id: Some(item_id.to_string()),
        call_id: call_id.to_string(),
        output: FunctionCallOutputPayload {
            body: codex_protocol::models::FunctionCallOutputBody::Text(text.to_string()),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    session
        .record_conversation_items(
            &turn_context,
            &[
                user_input_text("Verify the implementation."),
                plan("fresh-plan-active", "in_progress"),
                shell_call("fresh-test-call", "fresh-test", "pytest -q"),
                succeeded("fresh-test-output", "fresh-test", ""),
                plan("fresh-plan-closed", "completed"),
                shell_call("fresh-diff-call", "fresh-diff", "git diff --name-only"),
                succeeded("fresh-diff-output", "fresh-diff", "src/lib.py\n"),
                user_input_text("Continue after the edit."),
            ],
        )
        .await;

    let governed = build_governed_model_context_from_session(&session, &turn_context)
        .await
        .expect("governed session context should assemble");
    let model_text = joined_input_text(&governed.model_input);

    assert!(model_text.contains("awaiting_verification_count=1"));
    assert!(model_text.contains("status=awaiting_verification"));
    assert!(model_text.contains("verification_freshness: stale_count=1"));
}

#[tokio::test]
async fn governed_task_state_projects_navigation_refs_for_failed_test_and_changed_file() {
    let (session, turn_context) = crate::session::tests::make_session_and_context().await;
    let test_call = ResponseItem::FunctionCall {
        id: Some("navigation-verify-call".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({"command": "pytest -q", "workdir": "D:/repo"}).to_string(),
        call_id: "navigation-verify".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let failed_test = ResponseItem::FunctionCallOutput {
        id: Some("navigation-verify-output".to_string()),
        call_id: "navigation-verify".to_string(),
        output: FunctionCallOutputPayload {
            body: codex_protocol::models::FunctionCallOutputBody::Text(
                "FAILED tests/test_lib.py::test_behavior - expected value\n".to_string(),
            ),
            success: Some(false),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let diff_call = ResponseItem::FunctionCall {
        id: Some("navigation-diff-call".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({"command": "git diff --name-only", "workdir": "D:/repo"})
            .to_string(),
        call_id: "navigation-diff".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let diff_output = ResponseItem::FunctionCallOutput {
        id: Some("navigation-diff-output".to_string()),
        call_id: "navigation-diff".to_string(),
        output: FunctionCallOutputPayload {
            body: codex_protocol::models::FunctionCallOutputBody::Text("src/lib.py\n".to_string()),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    session
        .record_conversation_items(
            &turn_context,
            &[
                user_input_text("Repair the failing test."),
                test_call,
                failed_test,
                diff_call,
                diff_output,
                user_input_text("Continue with the map signal."),
            ],
        )
        .await;

    let governed = build_governed_model_context_from_session(&session, &turn_context)
        .await
        .expect("governed session context should assemble");
    let model_text = joined_input_text(&governed.model_input);

    assert_eq!(governed.manifest.graph_refs.len(), 1);
    assert_eq!(
        governed.manifest.graph_refs[0].expandable_ref,
        "repo-node://tests/test_lib.py"
    );
    assert!(model_text.contains("repo-node://tests/test_lib.py"));
}

#[tokio::test]
async fn governed_task_state_projects_verification_and_changed_file_records() {
    let (session, turn_context) = crate::session::tests::make_session_and_context().await;
    let test_call = ResponseItem::FunctionCall {
        id: Some("verify-call".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({
            "command": "pytest -q",
            "workdir": "D:/repo"
        })
        .to_string(),
        call_id: "verify-call-id".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let failed_test = ResponseItem::FunctionCallOutput {
        id: Some("verify-output".to_string()),
        call_id: "verify-call-id".to_string(),
        output: FunctionCallOutputPayload {
            body: codex_protocol::models::FunctionCallOutputBody::Text(
                "FAILED tests/test_api.py::test_denied - expected 403\n".to_string(),
            ),
            success: Some(false),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let diff_call = ResponseItem::FunctionCall {
        id: Some("diff-call".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({
            "command": "git diff --name-only",
            "workdir": "D:/repo"
        })
        .to_string(),
        call_id: "diff-call-id".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let diff_output = ResponseItem::FunctionCallOutput {
        id: Some("diff-output".to_string()),
        call_id: "diff-call-id".to_string(),
        output: FunctionCallOutputPayload {
            body: codex_protocol::models::FunctionCallOutputBody::Text(
                "src/lib.rs\ntests/test_api.py\n".to_string(),
            ),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    session
        .record_conversation_items(
            &turn_context,
            &[
                user_input_text("Inspect the verification results."),
                test_call,
                failed_test,
                diff_call,
                diff_output,
                user_input_text("Continue with the known state."),
            ],
        )
        .await;

    let governed = build_governed_model_context_from_session(&session, &turn_context)
        .await
        .expect("governed session context should assemble");
    let model_text = joined_input_text(&governed.model_input);

    assert!(model_text.contains("verifications: total_count=1 unresolved_failed_count=1"));
    assert!(model_text.contains("tests/test_api.py::test_denied"));
    assert!(model_text.contains("changed_file_snapshots: count=1"));
    assert!(model_text.contains("src/lib.rs,tests/test_api.py"));
}

#[tokio::test]
async fn plan_mode_uses_contributed_turn_item_for_last_agent_message() {
    let (mut session, turn_context) = crate::session::tests::make_session_and_context().await;
    let mut builder = codex_extension_api::ExtensionRegistryBuilder::new();
    builder.turn_item_contributor(Arc::new(RewriteAgentMessageContributor));
    session.services.extensions = Arc::new(builder.build());
    let turn_store = ExtensionData::new(turn_context.sub_id.clone());
    let mut state = PlanModeStreamState::new(&turn_context.sub_id);
    let mut last_agent_message = None;
    let item = assistant_output_text("original assistant text");

    let handled = handle_assistant_item_done_in_plan_mode(
        &session,
        &turn_context,
        &turn_store,
        &item,
        &mut state,
        /*previously_active_item*/ None,
        &mut last_agent_message,
    )
    .await;

    assert!(handled);
    assert_eq!(
        last_agent_message.as_deref(),
        Some("plan contributed assistant text")
    );
}
