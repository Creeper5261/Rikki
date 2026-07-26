use super::*;
use crate::session::tests::make_session_configuration_for_tests;
use crate::state::AutoCompactWindowSnapshot;
use codex_protocol::models::ContentItem;
use codex_protocol::models::FunctionCallOutputBody;
use codex_protocol::models::FunctionCallOutputPayload;
use codex_protocol::protocol::CreditsSnapshot;
use codex_protocol::protocol::RateLimitWindow;
use codex_protocol::protocol::RolloutItem;
use codex_protocol::protocol::SpendControlLimitSnapshot;
use pretty_assertions::assert_eq;

#[tokio::test]
// Verifies connector merging deduplicates repeated IDs.
async fn merge_connector_selection_deduplicates_entries() {
    let session_configuration = make_session_configuration_for_tests().await;
    let mut state = SessionState::new(session_configuration);
    let merged = state.merge_connector_selection([
        "calendar".to_string(),
        "calendar".to_string(),
        "drive".to_string(),
    ]);

    assert_eq!(
        merged,
        HashSet::from(["calendar".to_string(), "drive".to_string()])
    );
}

#[tokio::test]
// Verifies clearing connector selection removes all saved IDs.
async fn clear_connector_selection_removes_entries() {
    let session_configuration = make_session_configuration_for_tests().await;
    let mut state = SessionState::new(session_configuration);
    state.merge_connector_selection(["calendar".to_string()]);

    state.clear_connector_selection();

    assert_eq!(state.get_connector_selection(), HashSet::new());
}

#[tokio::test]
async fn set_rate_limits_defaults_limit_id_to_codex_when_missing() {
    let session_configuration = make_session_configuration_for_tests().await;
    let mut state = SessionState::new(session_configuration);

    state.set_rate_limits(RateLimitSnapshot {
        limit_id: None,
        limit_name: None,
        primary: Some(RateLimitWindow {
            used_percent: 12.0,
            window_minutes: Some(60),
            resets_at: Some(100),
        }),
        secondary: None,
        credits: None,
        individual_limit: None,
        plan_type: None,
        rate_limit_reached_type: None,
    });

    assert_eq!(
        state
            .latest_rate_limits
            .as_ref()
            .and_then(|v| v.limit_id.clone()),
        Some("codex".to_string())
    );
}

#[tokio::test]
async fn replace_history_clears_auto_compact_window_prefill() {
    let session_configuration = make_session_configuration_for_tests().await;
    let mut state = SessionState::new(session_configuration);

    state.set_auto_compact_window_estimated_prefill(/*tokens*/ 100);
    state.replace_history(Vec::new(), /*reference_context_item*/ None);

    assert_eq!(
        state.auto_compact_window_snapshot(),
        AutoCompactWindowSnapshot {
            prefill_input_tokens: None,
        }
    );
}

#[tokio::test]
async fn record_items_indexes_context_evidence_by_response_item_id() {
    let session_configuration = make_session_configuration_for_tests().await;
    let mut state = SessionState::new(session_configuration);
    let item = ResponseItem::Message {
        id: Some("msg-evidence".to_string()),
        role: "user".to_string(),
        content: vec![ContentItem::InputText {
            text: "Preserve this requirement as evidence.".to_string(),
        }],
        phase: None,
        internal_chat_message_metadata_passthrough: None,
    };

    state.record_items(std::slice::from_ref(&item), TruncationPolicy::Tokens(1_000));

    assert_eq!(
        state.context_evidence_refs(),
        HashMap::from([(
            "msg-evidence".to_string(),
            "evidence://response-item/msg-evidence?record=0".to_string(),
        )])
    );
    assert_eq!(
        state
            .context_task_state()
            .latest_requirement_revision()
            .and_then(|revision| revision.response_item_id.as_deref()),
        Some("msg-evidence")
    );
}

#[tokio::test]
async fn record_items_reduces_failed_tool_output_into_task_state() {
    let session_configuration = make_session_configuration_for_tests().await;
    let mut state = SessionState::new(session_configuration);
    let call = ResponseItem::FunctionCall {
        id: Some("call-failed-item".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({
            "command": "pytest -q",
            "workdir": "D:/repo"
        })
        .to_string(),
        call_id: "call-failed".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let item = ResponseItem::FunctionCallOutput {
        id: Some("output-failed".to_string()),
        call_id: "call-failed".to_string(),
        output: FunctionCallOutputPayload {
            body: Default::default(),
            success: Some(false),
        },
        internal_chat_message_metadata_passthrough: None,
    };

    state.record_items([&call, &item], TruncationPolicy::Tokens(1_000));

    let task_state = state.context_task_state();
    assert_eq!(task_state.tool_outcomes().len(), 1);
    assert_eq!(task_state.failed_tool_outcomes().count(), 1);
    assert_eq!(
        task_state.tool_outcomes()[0].category,
        crate::context_engine::ToolOutcomeCategory::Test
    );
    assert_eq!(
        task_state.tool_outcomes()[0].scope_paths,
        vec!["D:/repo".to_string()]
    );
}

#[tokio::test]
async fn rollout_rebuild_marks_tool_output_observed_only_after_model_response() {
    let session_configuration = make_session_configuration_for_tests().await;
    let output = ResponseItem::FunctionCallOutput {
        id: Some("tool-output-1".to_string()),
        call_id: "call-1".to_string(),
        output: FunctionCallOutputPayload {
            body: FunctionCallOutputBody::Text("raw output".to_string()),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let model_response = ResponseItem::Message {
        id: Some("assistant-after-output".to_string()),
        role: "assistant".to_string(),
        content: vec![ContentItem::OutputText {
            text: "I consumed the tool result.".to_string(),
        }],
        phase: None,
        internal_chat_message_metadata_passthrough: None,
    };

    let mut incomplete_state = SessionState::new(session_configuration.clone());
    incomplete_state
        .rebuild_context_evidence_from_rollout(&[RolloutItem::ResponseItem(output.clone())]);
    assert!(incomplete_state.tool_output_by_index(0).is_none());

    let mut observed_state = SessionState::new(session_configuration);
    observed_state.rebuild_context_evidence_from_rollout(&[
        RolloutItem::ResponseItem(output),
        RolloutItem::ResponseItem(model_response),
    ]);
    assert!(observed_state.tool_output_by_index(0).is_some());
}

#[tokio::test]
async fn set_rate_limits_defaults_to_codex_when_limit_id_missing_after_other_bucket() {
    let session_configuration = make_session_configuration_for_tests().await;
    let mut state = SessionState::new(session_configuration);

    state.set_rate_limits(RateLimitSnapshot {
        limit_id: Some("codex_other".to_string()),
        limit_name: Some("codex_other".to_string()),
        primary: Some(RateLimitWindow {
            used_percent: 20.0,
            window_minutes: Some(60),
            resets_at: Some(200),
        }),
        secondary: None,
        credits: None,
        individual_limit: None,
        plan_type: None,
        rate_limit_reached_type: None,
    });
    state.set_rate_limits(RateLimitSnapshot {
        limit_id: None,
        limit_name: None,
        primary: Some(RateLimitWindow {
            used_percent: 30.0,
            window_minutes: Some(60),
            resets_at: Some(300),
        }),
        secondary: None,
        credits: None,
        individual_limit: None,
        plan_type: None,
        rate_limit_reached_type: None,
    });

    assert_eq!(
        state
            .latest_rate_limits
            .as_ref()
            .and_then(|v| v.limit_id.clone()),
        Some("codex".to_string())
    );
}

#[tokio::test]
async fn set_rate_limits_carries_account_metadata_from_codex_to_codex_other() {
    let session_configuration = make_session_configuration_for_tests().await;
    let mut state = SessionState::new(session_configuration);

    state.set_rate_limits(RateLimitSnapshot {
        limit_id: Some("codex".to_string()),
        limit_name: Some("codex".to_string()),
        primary: Some(RateLimitWindow {
            used_percent: 10.0,
            window_minutes: Some(60),
            resets_at: Some(100),
        }),
        secondary: None,
        credits: Some(CreditsSnapshot {
            has_credits: true,
            unlimited: false,
            balance: Some("50".to_string()),
        }),
        individual_limit: Some(SpendControlLimitSnapshot {
            limit: "25000".to_string(),
            used: "8000".to_string(),
            remaining_percent: 68,
            resets_at: 300,
        }),
        plan_type: Some(codex_protocol::account::PlanType::Plus),
        rate_limit_reached_type: None,
    });

    state.set_rate_limits(RateLimitSnapshot {
        limit_id: Some("codex_other".to_string()),
        limit_name: None,
        primary: Some(RateLimitWindow {
            used_percent: 30.0,
            window_minutes: Some(120),
            resets_at: Some(200),
        }),
        secondary: None,
        credits: None,
        individual_limit: None,
        plan_type: None,
        rate_limit_reached_type: None,
    });

    assert_eq!(
        state.latest_rate_limits,
        Some(RateLimitSnapshot {
            limit_id: Some("codex_other".to_string()),
            limit_name: None,
            primary: Some(RateLimitWindow {
                used_percent: 30.0,
                window_minutes: Some(120),
                resets_at: Some(200),
            }),
            secondary: None,
            credits: Some(CreditsSnapshot {
                has_credits: true,
                unlimited: false,
                balance: Some("50".to_string()),
            }),
            individual_limit: Some(SpendControlLimitSnapshot {
                limit: "25000".to_string(),
                used: "8000".to_string(),
                remaining_percent: 68,
                resets_at: 300,
            }),
            plan_type: Some(codex_protocol::account::PlanType::Plus),
            rate_limit_reached_type: None,
        })
    );
}
