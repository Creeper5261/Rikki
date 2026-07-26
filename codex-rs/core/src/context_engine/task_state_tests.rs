use codex_protocol::models::FunctionCallOutputPayload;
use pretty_assertions::assert_eq;

use super::*;

#[test]
fn reducer_tracks_requirement_revisions_idempotently() {
    let evidence_id = EvidenceId(1);
    let item = ResponseItem::Message {
        id: Some("msg-requirement".to_string()),
        role: "user".to_string(),
        content: vec![ContentItem::InputText {
            text: "Keep changes scoped.".to_string(),
        }],
        phase: None,
        internal_chat_message_metadata_passthrough: None,
    };
    let delta = deterministic_task_delta_for_response_item(evidence_id, &item)
        .expect("user requirement should create a delta");
    let mut state = TaskState::default();

    assert!(state.apply(delta.clone()).is_some());
    assert!(state.apply(delta).is_none());
    assert_eq!(state.requirement_revisions().len(), 1);
    assert_eq!(
        state.latest_requirement_revision(),
        Some(&RequirementRevision {
            evidence_id,
            response_item_id: Some("msg-requirement".to_string()),
        })
    );
}

#[test]
fn reducer_tracks_failed_tool_outcomes() {
    let evidence_id = EvidenceId(2);
    let item = ResponseItem::FunctionCallOutput {
        id: Some("output-1".to_string()),
        call_id: "call-1".to_string(),
        output: FunctionCallOutputPayload {
            body: Default::default(),
            success: Some(false),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let delta = deterministic_task_delta_for_response_item(evidence_id, &item)
        .expect("tool output should create a delta");
    let note = delta.to_delta_note_input();
    let mut state = TaskState::default();

    assert!(state.apply(delta).is_some());
    assert_eq!(state.tool_outcomes().len(), 1);
    assert_eq!(state.failed_tool_outcomes().count(), 1);
    assert_eq!(note.kind, DeltaKind::OpenQuestion);
    assert_eq!(note.evidence_ids, vec![evidence_id]);
    let summary = state
        .render_context_summary(8)
        .expect("non-empty task state should render");
    assert!(summary.contains("TASK STATE SNAPSHOT"));
    assert!(summary.contains("unresolved_failed_count=1"));
    assert!(summary.contains("evidence://response-item/output-1?record=2"));
}

#[test]
fn reducer_correlates_test_outcomes_with_structured_call_scope() {
    let call = ResponseItem::FunctionCall {
        id: Some("call-item".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({
            "command": "pytest -q",
            "workdir": "D:/repo/tests"
        })
        .to_string(),
        call_id: "call-test".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let output = ResponseItem::FunctionCallOutput {
        id: Some("output-test".to_string()),
        call_id: "call-test".to_string(),
        output: FunctionCallOutputPayload {
            body: Default::default(),
            success: Some(false),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let mut state = TaskState::default();
    let call_delta = deterministic_task_delta_for_response_item(EvidenceId(3), &call)
        .expect("tool call should create a delta");
    let outcome_delta = deterministic_task_delta_for_response_item(EvidenceId(4), &output)
        .expect("tool output should create a delta");

    assert!(state.apply(call_delta).is_some());
    let applied_outcome = state
        .apply(outcome_delta)
        .expect("tool outcome should be applied");
    let outcome = state
        .tool_outcomes()
        .first()
        .expect("tool outcome should be stored");

    assert_eq!(outcome.tool_name.as_deref(), Some("shell_command"));
    assert_eq!(outcome.category, ToolOutcomeCategory::Test);
    assert_eq!(outcome.scope_paths, vec!["D:/repo/tests".to_string()]);
    assert_eq!(
        applied_outcome.to_delta_note_input().scope_paths,
        vec!["D:/repo/tests".to_string()]
    );

    let retry_call = ResponseItem::FunctionCall {
        id: Some("retry-call-item".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({
            "command": "pytest -q",
            "workdir": "D:/repo/tests"
        })
        .to_string(),
        call_id: "call-test-retry".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let retry_output = ResponseItem::FunctionCallOutput {
        id: Some("retry-output-test".to_string()),
        call_id: "call-test-retry".to_string(),
        output: FunctionCallOutputPayload {
            body: Default::default(),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let retry_call_delta = deterministic_task_delta_for_response_item(EvidenceId(5), &retry_call)
        .expect("retry tool call should create a delta");
    let retry_outcome_delta =
        deterministic_task_delta_for_response_item(EvidenceId(6), &retry_output)
            .expect("retry tool output should create a delta");

    assert!(state.apply(retry_call_delta).is_some());
    assert!(state.apply(retry_outcome_delta).is_some());
    assert_eq!(state.failed_tool_outcomes().count(), 0);
    assert_eq!(
        state.tool_outcomes()[0].resolution,
        ToolOutcomeResolution::SupersededBy(EvidenceId(6))
    );
    assert_eq!(
        state.retention_for_response_item(&output),
        ContextRetention::Recoverable
    );
    assert_eq!(
        state.retention_for_response_item(&retry_output),
        ContextRetention::Recoverable
    );
}

#[test]
fn reducer_marks_unresolved_failed_tool_call_and_output_as_open() {
    let call = ResponseItem::FunctionCall {
        id: Some("failed-call-item".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({"command": "pytest -q"}).to_string(),
        call_id: "failed-call".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let output = ResponseItem::FunctionCallOutput {
        id: Some("failed-output-item".to_string()),
        call_id: "failed-call".to_string(),
        output: FunctionCallOutputPayload {
            body: Default::default(),
            success: Some(false),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let mut state = TaskState::default();

    for (evidence_id, item) in [(EvidenceId(40), &call), (EvidenceId(41), &output)] {
        assert!(
            state
                .apply(
                    deterministic_task_delta_for_response_item(evidence_id, item)
                        .expect("tool item should create a delta")
                )
                .is_some()
        );
    }

    assert_eq!(
        state.retention_for_response_item(&call),
        ContextRetention::Open
    );
    assert_eq!(
        state.retention_for_response_item(&output),
        ContextRetention::Open
    );
}

#[test]
fn reducer_records_git_head_as_repository_freshness_anchor() {
    let commit = "0123456789abcdef0123456789abcdef01234567";
    let call = ResponseItem::FunctionCall {
        id: Some("git-call-item".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({
            "command": "git rev-parse HEAD",
            "workdir": "D:/repo"
        })
        .to_string(),
        call_id: "git-head".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let output = ResponseItem::FunctionCallOutput {
        id: Some("git-output-item".to_string()),
        call_id: "git-head".to_string(),
        output: FunctionCallOutputPayload {
            body: codex_protocol::models::FunctionCallOutputBody::Text(format!("{commit}\n")),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let mut state = TaskState::default();

    assert!(
        state
            .apply(
                deterministic_task_delta_for_response_item(EvidenceId(10), &call)
                    .expect("git call should create a delta")
            )
            .is_some()
    );
    assert!(
        state
            .apply(
                deterministic_task_delta_for_response_item(EvidenceId(11), &output)
                    .expect("git output should create a delta")
            )
            .is_some()
    );

    assert_eq!(
        state.repository_revisions().get("D:/repo"),
        Some(&RepositoryRevision {
            evidence_id: EvidenceId(11),
            response_item_id: Some("git-output-item".to_string()),
            workdir: Some("D:/repo".to_string()),
            commit: commit.to_string(),
            observed_turn_id: None,
        })
    );
    assert!(
        state
            .render_context_summary(8)
            .expect("repository revision should render")
            .contains(commit)
    );
}

#[test]
fn reducer_extracts_verification_failures_and_resolves_them_on_retry() {
    let call = ResponseItem::FunctionCall {
        id: Some("verification-call".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({
            "command": "pytest -q",
            "workdir": "D:/repo"
        })
        .to_string(),
        call_id: "verification-1".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let failed_output = ResponseItem::FunctionCallOutput {
        id: Some("verification-output-failed".to_string()),
        call_id: "verification-1".to_string(),
        output: FunctionCallOutputPayload {
            body: codex_protocol::models::FunctionCallOutputBody::Text(
                "FAILED tests/test_api.py::test_denied - expected 403\nsrc/lib.rs:5: error: mismatch\n"
                    .to_string(),
            ),
            success: Some(false),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let retry_call = ResponseItem::FunctionCall {
        id: Some("verification-call-retry".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({
            "command": "pytest -q",
            "workdir": "D:/repo"
        })
        .to_string(),
        call_id: "verification-2".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let passed_output = ResponseItem::FunctionCallOutput {
        id: Some("verification-output-passed".to_string()),
        call_id: "verification-2".to_string(),
        output: FunctionCallOutputPayload {
            body: Default::default(),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let mut state = TaskState::default();

    for (evidence_id, item) in [(EvidenceId(20), &call), (EvidenceId(21), &failed_output)] {
        assert!(
            state
                .apply(
                    deterministic_task_delta_for_response_item(evidence_id, item)
                        .expect("verification item should create a delta")
                )
                .is_some()
        );
    }

    assert_eq!(state.verification_records().len(), 1);
    assert_eq!(state.unresolved_failed_verifications().count(), 1);
    assert_eq!(
        state.verification_records()[0].failed_cases,
        vec!["tests/test_api.py::test_denied".to_string()]
    );
    assert_eq!(
        state.verification_records()[0].diagnostics,
        vec!["src/lib.rs:5: error: mismatch".to_string()]
    );

    for (evidence_id, item) in [
        (EvidenceId(22), &retry_call),
        (EvidenceId(23), &passed_output),
    ] {
        assert!(
            state
                .apply(
                    deterministic_task_delta_for_response_item(evidence_id, item)
                        .expect("retry item should create a delta")
                )
                .is_some()
        );
    }

    assert_eq!(state.unresolved_failed_verifications().count(), 0);
    assert_eq!(
        state.verification_records()[0].resolution,
        ToolOutcomeResolution::SupersededBy(EvidenceId(23))
    );
}

#[test]
fn reducer_extracts_git_diff_name_only_changed_files() {
    let call = ResponseItem::FunctionCall {
        id: Some("diff-call".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({
            "command": "git diff --name-only",
            "workdir": "D:/repo"
        })
        .to_string(),
        call_id: "diff-1".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let output = ResponseItem::FunctionCallOutput {
        id: Some("diff-output".to_string()),
        call_id: "diff-1".to_string(),
        output: FunctionCallOutputPayload {
            body: codex_protocol::models::FunctionCallOutputBody::Text(
                "src/lib.rs\ntests/test_lib.rs\n".to_string(),
            ),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let mut state = TaskState::default();

    for (evidence_id, item) in [(EvidenceId(30), &call), (EvidenceId(31), &output)] {
        assert!(
            state
                .apply(
                    deterministic_task_delta_for_response_item(evidence_id, item)
                        .expect("git item should create a delta")
                )
                .is_some()
        );
    }

    assert_eq!(
        state.changed_file_snapshots().get("D:/repo"),
        Some(&ChangedFilesSnapshot {
            evidence_id: EvidenceId(31),
            response_item_id: Some("diff-output".to_string()),
            workdir: Some("D:/repo".to_string()),
            files: vec!["src/lib.rs".to_string(), "tests/test_lib.rs".to_string()],
            observed_turn_id: None,
        })
    );
}

#[test]
fn reducer_projects_update_plan_items_as_explicit_subgoals() {
    let plan = update_plan_item(
        "plan-1",
        serde_json::json!([
            {"step": "Implement closure gate", "status": "in_progress"},
            {"step": "Run focused tests", "status": "pending"}
        ]),
    );
    let mut state = TaskState::default();

    assert!(
        state
            .apply(
                deterministic_task_delta_for_response_item(EvidenceId(50), &plan)
                    .expect("plan update should create a subgoal delta")
            )
            .is_some()
    );

    let mut subgoals = state.subgoals().collect::<Vec<_>>();
    subgoals.sort_by(|left, right| left.step.cmp(&right.step));
    assert_eq!(subgoals.len(), 2);
    assert_eq!(subgoals[0].step, "Implement closure gate");
    assert_eq!(subgoals[0].status, SubgoalStatus::Active);
    assert_eq!(subgoals[1].step, "Run focused tests");
    assert_eq!(subgoals[1].status, SubgoalStatus::Pending);
    assert!(
        state
            .render_context_summary(8)
            .expect("subgoals should make TaskState visible")
            .contains("active_count=1")
    );
}

#[test]
fn reducer_closes_completed_plan_item_without_verification_requirement() {
    let mut state = TaskState::default();
    for (evidence_id, status) in [(50, "in_progress"), (51, "completed")] {
        let plan = update_plan_item(
            &format!("plan-{evidence_id}"),
            serde_json::json!([{
                "step": "Review repository context",
                "status": status
            }]),
        );
        state.apply(
            deterministic_task_delta_for_response_item(EvidenceId(evidence_id), &plan)
                .expect("plan update should create a subgoal delta"),
        );
    }

    assert_eq!(
        state.subgoals().next().unwrap().status,
        SubgoalStatus::Closed
    );
}

#[test]
fn reducer_does_not_regress_closed_plan_item_from_repeated_stale_plan() {
    let mut state = TaskState::default();
    for (evidence_id, status) in [(50, "in_progress"), (51, "completed"), (52, "in_progress")] {
        let plan = update_plan_item(
            &format!("plan-{evidence_id}"),
            serde_json::json!([{
                "step": "Review repository context",
                "status": status
            }]),
        );
        state.apply(
            deterministic_task_delta_for_response_item(EvidenceId(evidence_id), &plan)
                .expect("plan update should create a subgoal delta"),
        );
    }

    assert_eq!(
        state.subgoals().next().unwrap().status,
        SubgoalStatus::Closed
    );
}

#[test]
fn reducer_refuses_to_close_completed_plan_item_without_verifier() {
    let plan = update_plan_item(
        "plan-2",
        serde_json::json!([{
            "step": "Implement closure gate",
            "status": "in_progress",
            "verification": {"command": "pytest -q", "scope": ["D:/repo"]}
        }]),
    );
    let completed = update_plan_item(
        "plan-3",
        serde_json::json!([{
            "step": "Implement closure gate",
            "status": "completed",
            "verification": {"command": "pytest -q", "scope": ["D:/repo"]}
        }]),
    );
    let mut state = TaskState::default();

    for (evidence_id, item) in [(EvidenceId(51), &plan), (EvidenceId(52), &completed)] {
        assert!(
            state
                .apply(
                    deterministic_task_delta_for_response_item(evidence_id, item)
                        .expect("plan update should create a subgoal delta")
                )
                .is_some()
        );
    }

    let subgoal = state.subgoals().next().expect("subgoal should be recorded");
    assert_eq!(subgoal.status, SubgoalStatus::AwaitingVerification);
    assert_eq!(subgoal.verifier_evidence_id, None);
}

#[test]
fn reducer_closes_completed_plan_item_only_after_successful_verifier() {
    let plan = update_plan_item(
        "plan-4",
        serde_json::json!([{
            "step": "Implement closure gate",
            "status": "in_progress",
            "verification": {"command": "pytest -q", "scope": ["D:/repo"]}
        }]),
    );
    let test_call = ResponseItem::FunctionCall {
        id: Some("closure-test-call".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({"command": "pytest -q", "workdir": "D:/repo"}).to_string(),
        call_id: "closure-test".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let test_output = ResponseItem::FunctionCallOutput {
        id: Some("closure-test-output".to_string()),
        call_id: "closure-test".to_string(),
        output: FunctionCallOutputPayload {
            body: Default::default(),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let completed = update_plan_item(
        "plan-5",
        serde_json::json!([{
            "step": "Implement closure gate",
            "status": "completed",
            "verification": {"command": "pytest -q", "scope": ["D:/repo"]}
        }]),
    );
    let mut state = TaskState::default();

    for (evidence_id, item) in [
        (EvidenceId(53), &plan),
        (EvidenceId(54), &test_call),
        (EvidenceId(55), &test_output),
        (EvidenceId(56), &completed),
    ] {
        assert!(
            state
                .apply(
                    deterministic_task_delta_for_response_item(evidence_id, item)
                        .expect("plan or verifier item should create a delta")
                )
                .is_some()
        );
    }

    let subgoal = state.subgoals().next().expect("subgoal should be recorded");
    assert_eq!(subgoal.status, SubgoalStatus::Closed);
    assert_eq!(subgoal.verifier_evidence_id, Some(EvidenceId(55)));
}

#[test]
fn reducer_does_not_close_subgoal_for_mismatched_verifier_contract() {
    let plan = update_plan_item(
        "plan-6",
        serde_json::json!([{
            "step": "Implement closure gate",
            "status": "in_progress",
            "verification": {"command": "pytest -q", "scope": ["D:/repo"]}
        }]),
    );
    let test_call = ResponseItem::FunctionCall {
        id: Some("mismatch-test-call".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({"command": "cargo test", "workdir": "D:/other"}).to_string(),
        call_id: "mismatch-test".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let test_output = ResponseItem::FunctionCallOutput {
        id: Some("mismatch-test-output".to_string()),
        call_id: "mismatch-test".to_string(),
        output: FunctionCallOutputPayload {
            body: Default::default(),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    };
    let completed = update_plan_item(
        "plan-7",
        serde_json::json!([{
            "step": "Implement closure gate",
            "status": "completed",
            "verification": {"command": "pytest -q", "scope": ["D:/repo"]}
        }]),
    );
    let mut state = TaskState::default();

    for (evidence_id, item) in [
        (EvidenceId(57), &plan),
        (EvidenceId(58), &test_call),
        (EvidenceId(59), &test_output),
        (EvidenceId(60), &completed),
    ] {
        state.apply(
            deterministic_task_delta_for_response_item(evidence_id, item)
                .expect("plan or verifier item should create a delta"),
        );
    }

    let subgoal = state.subgoals().next().expect("subgoal should be recorded");
    assert_eq!(subgoal.status, SubgoalStatus::AwaitingVerification);
    assert_eq!(subgoal.verifier_evidence_id, None);
}

#[test]
fn reducer_builds_bounded_navigation_signal_from_failed_test_and_changed_file() {
    let test_call = ResponseItem::FunctionCall {
        id: Some("navigation-test-call".to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({"command": "pytest -q", "workdir": "D:/repo"}).to_string(),
        call_id: "navigation-test".to_string(),
        internal_chat_message_metadata_passthrough: None,
    };
    let failed_test = ResponseItem::FunctionCallOutput {
        id: Some("navigation-test-output".to_string()),
        call_id: "navigation-test".to_string(),
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
    let mut state = TaskState::default();

    for (evidence_id, item) in [
        (EvidenceId(60), &test_call),
        (EvidenceId(61), &failed_test),
        (EvidenceId(62), &diff_call),
        (EvidenceId(63), &diff_output),
    ] {
        state.apply(
            deterministic_task_delta_for_response_item(evidence_id, item)
                .expect("navigation input should create a delta"),
        );
    }

    let signals = state.navigation_graph_signals();
    assert_eq!(signals.len(), 1);
    assert_eq!(signals[0].seed, GraphSeed::Path("src/lib.py".to_string()));
    assert_eq!(
        signals[0].candidates[0].expandable_ref,
        "repo-node://tests/test_lib.py"
    );
}

#[test]
fn reducer_reopens_closed_subgoal_when_dirty_snapshot_follows_its_verifier() {
    let plan = update_plan_item(
        "freshness-plan-active",
        serde_json::json!([{
            "step": "Verify implementation",
            "status": "in_progress",
            "verification": {"command": "pytest -q", "scope": ["D:/repo"]}
        }]),
    );
    let test_call = shell_call(
        "freshness-test-call",
        "freshness-test",
        "pytest -q",
        "D:/repo",
    );
    let passed_test = successful_output("freshness-test-output", "freshness-test", "");
    let completed = update_plan_item(
        "freshness-plan-completed",
        serde_json::json!([{
            "step": "Verify implementation",
            "status": "completed",
            "verification": {"command": "pytest -q", "scope": ["D:/repo"]}
        }]),
    );
    let diff_call = shell_call(
        "freshness-diff-call",
        "freshness-diff",
        "git diff --name-only",
        "D:/repo",
    );
    let dirty_diff = successful_output("freshness-diff-output", "freshness-diff", "src/lib.py\n");
    let mut state = TaskState::default();

    for (evidence_id, item) in [
        (EvidenceId(70), &plan),
        (EvidenceId(71), &test_call),
        (EvidenceId(72), &passed_test),
        (EvidenceId(73), &completed),
    ] {
        state.apply(
            deterministic_task_delta_for_response_item(evidence_id, item)
                .expect("plan or verifier item should create a delta"),
        );
    }
    assert_eq!(
        state.subgoals().next().unwrap().status,
        SubgoalStatus::Closed
    );

    for (evidence_id, item) in [(EvidenceId(74), &diff_call), (EvidenceId(75), &dirty_diff)] {
        state.apply(
            deterministic_task_delta_for_response_item(evidence_id, item)
                .expect("diff item should create a delta"),
        );
    }

    assert_eq!(
        state.subgoals().next().unwrap().status,
        SubgoalStatus::AwaitingVerification
    );
    assert_eq!(state.stale_verifications().count(), 1);
    assert!(
        state
            .render_context_summary(8)
            .unwrap()
            .contains("stale_count=1")
    );

    let clean_diff_call = shell_call(
        "freshness-clean-diff-call",
        "freshness-clean-diff",
        "git diff --name-only",
        "D:/repo",
    );
    let clean_diff = successful_output("freshness-clean-diff-output", "freshness-clean-diff", "");
    let stale_reclose = update_plan_item(
        "freshness-plan-stale-reclose",
        serde_json::json!([{
            "step": "Verify implementation",
            "status": "completed",
            "verification": {"command": "pytest -q", "scope": ["D:/repo"]}
        }]),
    );
    for (evidence_id, item) in [
        (EvidenceId(76), &clean_diff_call),
        (EvidenceId(77), &clean_diff),
        (EvidenceId(78), &stale_reclose),
    ] {
        state.apply(
            deterministic_task_delta_for_response_item(evidence_id, item)
                .expect("clean diff or plan item should create a delta"),
        );
    }
    assert_eq!(
        state.subgoals().next().unwrap().status,
        SubgoalStatus::AwaitingVerification
    );
    assert_eq!(state.stale_verifications().count(), 1);
    assert_eq!(state.latest_dirty_file_snapshots().len(), 1);

    let retry_call = shell_call(
        "freshness-retry-call",
        "freshness-retry",
        "pytest -q",
        "D:/repo",
    );
    let retry_output = successful_output("freshness-retry-output", "freshness-retry", "");
    let reclose = update_plan_item(
        "freshness-plan-reclosed",
        serde_json::json!([{
            "step": "Verify implementation",
            "status": "completed",
            "verification": {"command": "pytest -q", "scope": ["D:/repo"]}
        }]),
    );
    for (evidence_id, item) in [
        (EvidenceId(79), &retry_call),
        (EvidenceId(80), &retry_output),
        (EvidenceId(81), &reclose),
    ] {
        state.apply(
            deterministic_task_delta_for_response_item(evidence_id, item)
                .expect("reverification item should create a delta"),
        );
    }

    let subgoal = state.subgoals().next().unwrap();
    assert_eq!(subgoal.status, SubgoalStatus::Closed);
    assert_eq!(subgoal.verifier_evidence_id, Some(EvidenceId(80)));
    assert_eq!(state.stale_verifications().count(), 1);
}

fn shell_call(item_id: &str, call_id: &str, command: &str, workdir: &str) -> ResponseItem {
    ResponseItem::FunctionCall {
        id: Some(item_id.to_string()),
        name: "shell_command".to_string(),
        namespace: None,
        arguments: serde_json::json!({"command": command, "workdir": workdir}).to_string(),
        call_id: call_id.to_string(),
        internal_chat_message_metadata_passthrough: None,
    }
}

fn successful_output(item_id: &str, call_id: &str, text: &str) -> ResponseItem {
    ResponseItem::FunctionCallOutput {
        id: Some(item_id.to_string()),
        call_id: call_id.to_string(),
        output: FunctionCallOutputPayload {
            body: codex_protocol::models::FunctionCallOutputBody::Text(text.to_string()),
            success: Some(true),
        },
        internal_chat_message_metadata_passthrough: None,
    }
}

fn update_plan_item(id: &str, plan: serde_json::Value) -> ResponseItem {
    ResponseItem::FunctionCall {
        id: Some(id.to_string()),
        name: "update_plan".to_string(),
        namespace: None,
        arguments: serde_json::json!({"plan": plan}).to_string(),
        call_id: format!("{id}-call"),
        internal_chat_message_metadata_passthrough: None,
    }
}
