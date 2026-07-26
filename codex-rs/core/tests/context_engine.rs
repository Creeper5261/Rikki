use codex_core::context_engine::ContextBudget;
use codex_core::context_engine::ContextGovernanceError;
use codex_core::context_engine::ContextItem;
use codex_core::context_engine::ContextItemKind;
use codex_core::context_engine::ContextRetention;
use codex_core::context_engine::DeltaKind;
use codex_core::context_engine::DeltaNoteInput;
use codex_core::context_engine::DensityPolicy;
use codex_core::context_engine::EvidenceKind;
use codex_core::context_engine::EvidenceRecordInput;
use codex_core::context_engine::GovernedContextAssembler;
use codex_core::context_engine::GovernedContextCandidate;
use codex_core::context_engine::GovernedContextRequest;
use codex_core::context_engine::GraphBudget;
use codex_core::context_engine::GraphSeed;
use codex_core::context_engine::InMemoryEvidenceLedger;
use codex_core::context_engine::InMemoryRepoGraph;
use codex_core::context_engine::RepoEdgeKind;
use codex_core::context_engine::RepoNodeKind;
use codex_core::context_engine::response_item_id_from_evidence_ref;
use codex_protocol::models::ContentItem;
use codex_protocol::models::FunctionCallOutputPayload;
use codex_protocol::models::ResponseItem;

#[test]
fn evidence_ledger_records_raw_evidence_and_links_delta() {
    let mut ledger = InMemoryEvidenceLedger::new();

    let evidence_id = ledger.record_evidence(EvidenceRecordInput {
        turn_id: "turn-1".to_string(),
        step_index: 3,
        source_kind: EvidenceKind::ToolOutput,
        raw_ref: "rollout://turn-1/item-7".to_string(),
        content_hash: "sha1:fixture".to_string(),
        token_estimate: 3_000,
        related_response_item_id: Some("item-7".to_string()),
    });

    let delta_id = ledger.record_delta(DeltaNoteInput {
        evidence_ids: vec![evidence_id],
        kind: DeltaKind::Correction,
        statement: "DummyLoader requires the app keyword argument.".to_string(),
        invalidated_assumption: Some(
            "DummyLoader could be constructed without kwargs.".to_string(),
        ),
        scope_paths: vec!["tests/loader_test.py".to_string()],
        confidence: 0.9,
    });

    let evidence = ledger.evidence(evidence_id).expect("evidence should exist");
    assert_eq!(evidence.source_kind, EvidenceKind::ToolOutput);
    assert_eq!(evidence.token_estimate, 3_000);

    let delta = ledger.delta(delta_id).expect("delta should exist");
    assert_eq!(delta.evidence_ids, vec![evidence_id]);
    assert_eq!(delta.kind, DeltaKind::Correction);
    assert_eq!(ledger.deltas_for_evidence(evidence_id), vec![delta_id]);
}

#[test]
fn evidence_ledger_indexes_response_items_with_stable_refs() {
    let mut ledger = InMemoryEvidenceLedger::new();
    let item = ResponseItem::Message {
        id: Some("msg-1".to_string()),
        role: "user".to_string(),
        content: vec![ContentItem::InputText {
            text: "Keep the evidence recoverable.".to_string(),
        }],
        phase: None,
        internal_chat_message_metadata_passthrough: None,
    };

    let evidence_id = ledger.record_response_item(&item);
    let duplicate_evidence_id = ledger.record_response_item(&item);
    let evidence = ledger
        .evidence_for_response_item_id("msg-1")
        .expect("response item evidence should be indexed");

    assert_eq!(evidence.id, evidence_id);
    assert_eq!(duplicate_evidence_id, evidence_id);
    assert_eq!(evidence.source_kind, EvidenceKind::UserInput);
    assert_eq!(evidence.raw_ref, "rollout://response-item/msg-1");
    assert!(!evidence.content_hash.is_empty());
    assert_eq!(
        ledger.evidence_refs_by_response_item_id(),
        std::collections::HashMap::from([(
            "msg-1".to_string(),
            format!("evidence://response-item/msg-1?record={evidence_id}"),
        )])
    );
}

#[test]
fn evidence_ref_parser_accepts_record_query_and_rejects_other_schemes() {
    assert_eq!(
        response_item_id_from_evidence_ref("evidence://response-item/msg-1?record=7"),
        Some("msg-1")
    );
    assert_eq!(
        response_item_id_from_evidence_ref("evidence://response-item/msg-2"),
        Some("msg-2")
    );
    assert_eq!(response_item_id_from_evidence_ref("history://item/2"), None);
    assert_eq!(
        response_item_id_from_evidence_ref("evidence://response-item/"),
        None
    );
}

#[test]
fn density_policy_selects_high_value_items_within_budget() {
    let items = vec![
        ContextItem::new("requirement", ContextItemKind::UserRequirement, 40)
            .with_utility_score(1.0)
            .with_freshness(1.0),
        ContextItem::new("active-delta", ContextItemKind::Delta, 25)
            .with_utility_score(0.95)
            .with_freshness(1.0),
        ContextItem::new("large-log", ContextItemKind::ToolOutput, 300)
            .with_utility_score(0.05)
            .with_freshness(0.5),
    ];

    let manifest = DensityPolicy.select(
        items,
        ContextBudget {
            max_tokens: 80,
            min_density: 0.01,
        },
    );

    assert_eq!(manifest.total_selected_tokens, 65);
    assert!(manifest.selected_ids().contains(&"requirement"));
    assert!(manifest.selected_ids().contains(&"active-delta"));
    assert!(manifest.dropped_ids().contains(&"large-log"));
    assert!(manifest.density_score >= manifest.budget.min_density);
}

#[test]
fn density_policy_treats_token_overflow_as_over_budget() {
    let items = vec![
        ContextItem::new("small", ContextItemKind::Delta, 1)
            .with_utility_score(1.0)
            .with_freshness(1.0),
        ContextItem::new("huge", ContextItemKind::ToolOutput, u32::MAX)
            .with_utility_score(1.0)
            .with_freshness(1.0),
    ];

    let manifest = DensityPolicy.select(
        items,
        ContextBudget {
            max_tokens: u32::MAX,
            min_density: 0.0,
        },
    );

    assert_eq!(manifest.selected_ids(), vec!["small"]);
    assert_eq!(manifest.dropped_ids(), vec!["huge"]);
    assert_eq!(manifest.total_selected_tokens, 1);
}

#[test]
fn density_policy_prioritizes_open_evidence_over_higher_density_optional_context() {
    let manifest = DensityPolicy.select(
        vec![
            ContextItem::new("optional-high-density", ContextItemKind::Delta, 50)
                .with_utility_score(100.0),
            ContextItem::new("open-failure", ContextItemKind::ToolOutput, 50)
                .with_utility_score(0.01)
                .with_retention(ContextRetention::Open),
        ],
        ContextBudget {
            max_tokens: 50,
            min_density: 0.01,
        },
    );

    assert_eq!(manifest.selected_ids(), vec!["open-failure"]);
    assert_eq!(manifest.dropped_ids(), vec!["optional-high-density"]);
}

#[test]
fn density_policy_drops_recoverable_evidence_before_optional_active_context() {
    let manifest = DensityPolicy.select(
        vec![
            ContextItem::new("recoverable-log", ContextItemKind::ToolOutput, 50)
                .with_utility_score(100.0)
                .with_retention(ContextRetention::Recoverable),
            ContextItem::new("active-context", ContextItemKind::Delta, 50).with_utility_score(0.01),
        ],
        ContextBudget {
            max_tokens: 50,
            min_density: 0.0,
        },
    );

    assert_eq!(manifest.selected_ids(), vec!["active-context"]);
    assert_eq!(manifest.dropped_ids(), vec!["recoverable-log"]);
}

#[test]
fn graph_signal_returns_markov_blanket_candidates_without_content_dump() {
    let mut graph = InMemoryRepoGraph::new();
    let file = graph.add_node(RepoNodeKind::File, "src/loader.py", None);
    let test = graph.add_node(RepoNodeKind::Test, "tests/loader_test.py", None);
    graph.add_edge(
        test,
        file,
        RepoEdgeKind::TestCovers,
        0.9,
        "test exercises loader behavior",
    );

    let candidate_set = graph.markov_blanket_candidates(
        GraphSeed::Path("src/loader.py".to_string()),
        GraphBudget {
            max_candidates: 8,
            max_reason_edges: 4,
        },
    );

    assert_eq!(
        candidate_set.seed,
        GraphSeed::Path("src/loader.py".to_string())
    );
    assert_eq!(candidate_set.candidates.len(), 1);
    let candidate = &candidate_set.candidates[0];
    assert_eq!(candidate.node_id, test);
    assert_eq!(candidate.reason_edges.len(), 1);
    assert_eq!(candidate.expandable_ref, "repo-node://tests/loader_test.py");
    assert!(candidate.content_preview.is_none());
}

#[test]
fn governed_context_assembler_exposes_only_selected_items_and_manifest() {
    let selected = GovernedContextCandidate::new(
        ContextItem::new("req-1", ContextItemKind::UserRequirement, 40)
            .with_utility_score(1.0)
            .with_freshness(1.0),
        text_message(
            "developer",
            "Selected requirement: keep edits tightly scoped.",
        ),
    )
    .with_source_ref("evidence://req-1");
    let dropped = GovernedContextCandidate::new(
        ContextItem::new("large-log", ContextItemKind::ToolOutput, 400)
            .with_utility_score(0.01)
            .with_freshness(0.5),
        text_message("user", "VERY_LARGE_STACKTRACE_SHOULD_NOT_REACH_MODEL"),
    )
    .with_source_ref("rollout://turn-1/tool-large-log");

    let governed = GovernedContextAssembler
        .assemble(GovernedContextRequest {
            candidates: vec![selected, dropped],
            graph_signals: Vec::new(),
            current_user_input: text_message("user", "Please continue the runtime integration."),
            budget: ContextBudget {
                max_tokens: 80,
                min_density: 0.01,
            },
        })
        .expect("governed context should assemble");

    assert_eq!(governed.manifest.selected_ids(), vec!["req-1"]);
    assert_eq!(governed.manifest.dropped_ids(), vec!["large-log"]);
    assert_eq!(
        governed
            .manifest
            .dropped
            .first()
            .expect("dropped item should be audited")
            .source_ref,
        "rollout://turn-1/tool-large-log"
    );

    let model_text = joined_model_text(&governed.model_input);
    assert!(model_text.contains("CONTEXT GOVERNANCE NOTE"));
    assert!(model_text.contains("Selected requirement: keep edits tightly scoped."));
    assert!(model_text.contains("Please continue the runtime integration."));
    assert!(!model_text.contains("VERY_LARGE_STACKTRACE_SHOULD_NOT_REACH_MODEL"));
}

#[test]
fn governed_context_assembler_preserves_selected_input_order() {
    let older_lower_density = GovernedContextCandidate::new(
        ContextItem::new("older-requirement", ContextItemKind::UserRequirement, 50)
            .with_utility_score(0.5)
            .with_freshness(1.0),
        text_message("user", "OLDER_SELECTED_REQUIREMENT"),
    );
    let newer_higher_density = GovernedContextCandidate::new(
        ContextItem::new("newer-delta", ContextItemKind::Delta, 10)
            .with_utility_score(1.0)
            .with_freshness(1.0),
        text_message("assistant", "NEWER_SELECTED_DELTA"),
    );

    let governed = GovernedContextAssembler
        .assemble(GovernedContextRequest {
            candidates: vec![older_lower_density, newer_higher_density],
            graph_signals: Vec::new(),
            current_user_input: text_message("user", "Continue."),
            budget: ContextBudget {
                max_tokens: 80,
                min_density: 0.001,
            },
        })
        .expect("governed context should assemble");

    assert_eq!(
        governed.manifest.selected_ids(),
        vec!["older-requirement", "newer-delta"]
    );

    let model_text = joined_model_text(&governed.model_input);
    let older_index = model_text
        .find("OLDER_SELECTED_REQUIREMENT")
        .expect("older selected item should be visible");
    let newer_index = model_text
        .find("NEWER_SELECTED_DELTA")
        .expect("newer selected item should be visible");
    assert!(older_index < newer_index);
}

#[test]
fn governed_context_assembler_bounds_model_visible_manifest_lists() {
    let dropped_candidates = (0..30)
        .map(|index| {
            let id = format!("drop-{index:02}");
            GovernedContextCandidate::new(
                ContextItem::new(id, ContextItemKind::ToolOutput, 10)
                    .with_utility_score(0.001)
                    .with_freshness(1.0),
                text_message("user", &format!("DROPPED_PAYLOAD_{index:02}")),
            )
            .with_source_ref(format!("rollout://dropped/{index:02}"))
        })
        .collect();

    let governed = GovernedContextAssembler
        .assemble(GovernedContextRequest {
            candidates: dropped_candidates,
            graph_signals: Vec::new(),
            current_user_input: text_message("user", "Continue with a bounded manifest."),
            budget: ContextBudget {
                max_tokens: 1_000,
                min_density: 1.0,
            },
        })
        .expect("governed context should assemble");

    assert_eq!(governed.manifest.dropped.len(), 30);

    let model_text = joined_model_text(&governed.model_input);
    assert!(model_text.contains("deferred_count=30"));
    assert!(model_text.contains("deferred_context:"));
    assert!(model_text.contains("recovery metadata is retained outside model context"));
    assert!(model_text.contains("get_tool_output(index, cursor?, max_chars?)"));
    assert!(!model_text.contains("rollout://dropped/00"));
    assert!(!model_text.contains("rollout://dropped/29"));
    assert!(!model_text.contains("DROPPED_PAYLOAD_29"));
}

#[test]
fn governed_context_model_note_does_not_repeat_selected_context_metadata() {
    let selected = GovernedContextCandidate::new(
        ContextItem::new("selected-requirement", ContextItemKind::UserRequirement, 10),
        text_message("user", "SELECTED_REQUIREMENT_PAYLOAD"),
    )
    .with_source_ref("evidence://response-item/selected?record=1");

    let governed = GovernedContextAssembler
        .assemble(GovernedContextRequest {
            candidates: vec![selected],
            graph_signals: Vec::new(),
            current_user_input: text_message("user", "Continue."),
            budget: ContextBudget {
                max_tokens: 80,
                min_density: 0.0,
            },
        })
        .expect("governed context should assemble");

    let note = joined_model_text(&governed.model_input[..1]);
    assert!(note.contains("CONTEXT GOVERNANCE NOTE"));
    assert!(!note.contains("selected-requirement"));
    assert!(!note.contains("evidence://response-item/selected?record=1"));
}

#[test]
fn governed_context_assembler_renders_graph_refs_without_content_preview() {
    let mut graph = InMemoryRepoGraph::new();
    let file = graph.add_node(RepoNodeKind::File, "src/loader.py", None);
    let test = graph.add_node(RepoNodeKind::Test, "tests/loader_test.py", None);
    graph.add_edge(
        test,
        file,
        RepoEdgeKind::TestCovers,
        0.9,
        "test exercises loader behavior",
    );

    let mut graph_signal = graph.markov_blanket_candidates(
        GraphSeed::Path("src/loader.py".to_string()),
        GraphBudget {
            max_candidates: 8,
            max_reason_edges: 4,
        },
    );
    graph_signal.candidates[0].content_preview =
        Some("def loader_secret_implementation(): pass".to_string());

    let governed = GovernedContextAssembler
        .assemble(GovernedContextRequest {
            candidates: Vec::new(),
            graph_signals: vec![graph_signal],
            current_user_input: text_message("user", "Inspect loader behavior."),
            budget: ContextBudget {
                max_tokens: 80,
                min_density: 0.01,
            },
        })
        .expect("governed context should assemble");

    assert_eq!(
        governed
            .manifest
            .graph_refs
            .first()
            .expect("graph ref should be audited")
            .expandable_ref,
        "repo-node://tests/loader_test.py"
    );

    let model_text = joined_model_text(&governed.model_input);
    assert!(model_text.contains("repo-node://tests/loader_test.py"));
    assert!(!model_text.contains("loader_secret_implementation"));
}

#[test]
fn governed_context_assembler_fails_closed_when_selected_item_has_no_payload() {
    let selected_without_payload = GovernedContextCandidate::deferred(
        ContextItem::new("delta-1", ContextItemKind::Delta, 30)
            .with_utility_score(1.0)
            .with_freshness(1.0),
    )
    .with_source_ref("delta://delta-1");

    let err = GovernedContextAssembler
        .assemble(GovernedContextRequest {
            candidates: vec![selected_without_payload],
            graph_signals: Vec::new(),
            current_user_input: text_message("user", "Continue."),
            budget: ContextBudget {
                max_tokens: 80,
                min_density: 0.01,
            },
        })
        .expect_err("governance must fail closed instead of falling back to raw history");

    assert_eq!(err.selected_id(), Some("delta-1"));
}

#[test]
fn governed_context_assembler_drops_function_call_when_its_output_is_dropped() {
    let function_call = GovernedContextCandidate::new(
        ContextItem::new("call", ContextItemKind::Delta, 10)
            .with_utility_score(1.0)
            .with_freshness(1.0),
        ResponseItem::FunctionCall {
            id: None,
            name: "shell_command".to_string(),
            namespace: None,
            arguments: "{}".to_string(),
            call_id: "call-1".to_string(),
            internal_chat_message_metadata_passthrough: None,
        },
    );
    let function_output = GovernedContextCandidate::new(
        ContextItem::new("output", ContextItemKind::ToolOutput, 400)
            .with_utility_score(0.01)
            .with_freshness(1.0),
        ResponseItem::FunctionCallOutput {
            id: None,
            call_id: "call-1".to_string(),
            output: FunctionCallOutputPayload::from_text("tool output".to_string()),
            internal_chat_message_metadata_passthrough: None,
        },
    );

    let governed = GovernedContextAssembler
        .assemble(GovernedContextRequest {
            candidates: vec![function_call, function_output],
            graph_signals: Vec::new(),
            current_user_input: text_message("user", "Continue."),
            budget: ContextBudget {
                max_tokens: 1_000,
                min_density: 0.01,
            },
        })
        .expect("governed context should assemble");

    assert_eq!(governed.manifest.selected_ids(), Vec::<&str>::new());
    assert_eq!(governed.manifest.dropped_ids(), vec!["call", "output"]);
    assert!(!governed.model_input.iter().any(
        |item| matches!(item, ResponseItem::FunctionCall { call_id, .. } if call_id == "call-1")
    ));
    assert!(!governed
        .model_input
        .iter()
        .any(|item| matches!(item, ResponseItem::FunctionCallOutput { call_id, .. } if call_id == "call-1")));
}

#[test]
fn governed_context_assembler_keeps_open_tool_call_and_output_together() {
    let function_call = GovernedContextCandidate::new(
        ContextItem::new("open-call", ContextItemKind::Delta, 10)
            .with_retention(ContextRetention::Open),
        function_call_item("open-call-1"),
    );
    let function_output = GovernedContextCandidate::new(
        ContextItem::new("open-output", ContextItemKind::ToolOutput, 20)
            .with_retention(ContextRetention::Open),
        function_output_item("open-call-1", "failed test output"),
    );
    let optional = GovernedContextCandidate::new(
        ContextItem::new("optional", ContextItemKind::Delta, 30).with_utility_score(100.0),
        text_message("assistant", "OPTIONAL_CONTEXT"),
    );

    let governed = GovernedContextAssembler
        .assemble(GovernedContextRequest {
            candidates: vec![function_call, function_output, optional],
            graph_signals: Vec::new(),
            current_user_input: text_message("user", "Continue."),
            budget: ContextBudget {
                max_tokens: 30,
                min_density: 0.0,
            },
        })
        .expect("open tool call and output should fit and remain visible");

    assert_eq!(
        governed.manifest.selected_ids(),
        vec!["open-call", "open-output"]
    );
    assert_eq!(governed.manifest.dropped_ids(), vec!["optional"]);
    assert!(
        governed
            .manifest
            .selected
            .iter()
            .all(|item| item.item.retention == ContextRetention::Open)
    );
    assert!(governed.model_input.iter().any(
        |item| matches!(item, ResponseItem::FunctionCallOutput { call_id, .. } if call_id == "open-call-1")
    ));
}

#[test]
fn governed_context_assembler_restores_optional_mate_for_selected_open_tool_output() {
    let function_call = GovernedContextCandidate::new(
        ContextItem::new("call", ContextItemKind::Delta, 10)
            .with_utility_score(0.0)
            .with_freshness(0.0),
        function_call_item("call-1"),
    );
    let function_output = GovernedContextCandidate::new(
        ContextItem::new("open-output", ContextItemKind::ToolOutput, 20)
            .with_retention(ContextRetention::Open),
        function_output_item("call-1", "failed test output"),
    );

    let governed = GovernedContextAssembler
        .assemble(GovernedContextRequest {
            candidates: vec![function_call, function_output],
            graph_signals: Vec::new(),
            current_user_input: text_message("user", "Continue."),
            budget: ContextBudget {
                max_tokens: 30,
                min_density: 0.01,
            },
        })
        .expect("the optional call should be restored to keep open output usable");

    assert_eq!(
        governed.manifest.selected_ids(),
        vec!["call", "open-output"]
    );
    assert_eq!(governed.manifest.dropped_ids(), Vec::<&str>::new());
}

#[test]
fn governed_context_assembler_evicts_optional_item_to_restore_open_tool_pair() {
    let governed = GovernedContextAssembler
        .assemble(GovernedContextRequest {
            candidates: vec![
                GovernedContextCandidate::new(
                    ContextItem::new("call", ContextItemKind::Delta, 10)
                        .with_utility_score(0.0)
                        .with_freshness(0.0),
                    function_call_item("call-1"),
                ),
                GovernedContextCandidate::new(
                    ContextItem::new("open-output", ContextItemKind::ToolOutput, 20)
                        .with_retention(ContextRetention::Open),
                    function_output_item("call-1", "failed test output"),
                ),
                GovernedContextCandidate::new(
                    ContextItem::new("optional", ContextItemKind::Delta, 10)
                        .with_utility_score(100.0),
                    text_message("assistant", "OPTIONAL_CONTEXT"),
                ),
            ],
            graph_signals: Vec::new(),
            current_user_input: text_message("user", "Continue."),
            budget: ContextBudget {
                max_tokens: 30,
                min_density: 0.0,
            },
        })
        .expect("optional context should yield to a retained tool pair");

    assert_eq!(
        governed.manifest.selected_ids(),
        vec!["call", "open-output"]
    );
    assert_eq!(governed.manifest.dropped_ids(), vec!["optional"]);
}

#[test]
fn governed_context_assembler_fails_closed_when_open_tool_pair_exceeds_budget() {
    let err = GovernedContextAssembler
        .assemble(GovernedContextRequest {
            candidates: vec![
                GovernedContextCandidate::new(
                    ContextItem::new("open-call", ContextItemKind::Delta, 10)
                        .with_retention(ContextRetention::Open),
                    function_call_item("open-call-1"),
                ),
                GovernedContextCandidate::new(
                    ContextItem::new("open-output", ContextItemKind::ToolOutput, 20)
                        .with_retention(ContextRetention::Open),
                    function_output_item("open-call-1", "failed test output"),
                ),
            ],
            graph_signals: Vec::new(),
            current_user_input: text_message("user", "Continue."),
            budget: ContextBudget {
                max_tokens: 20,
                min_density: 0.0,
            },
        })
        .expect_err("open evidence cannot be silently removed to satisfy a budget");

    assert_eq!(
        err,
        ContextGovernanceError::RetainedItemDropped {
            selected_id: "open-call".to_string(),
        }
    );
}

#[test]
fn governed_context_manifest_audits_recoverable_retention() {
    let governed = GovernedContextAssembler
        .assemble(GovernedContextRequest {
            candidates: vec![GovernedContextCandidate::new(
                ContextItem::new("recoverable-log", ContextItemKind::ToolOutput, 100)
                    .with_retention(ContextRetention::Recoverable),
                text_message("user", "RECOVERABLE_LOG"),
            )],
            graph_signals: Vec::new(),
            current_user_input: text_message("user", "Continue."),
            budget: ContextBudget {
                max_tokens: 1,
                min_density: 0.0,
            },
        })
        .expect("recoverable evidence can be deferred");

    assert_eq!(
        governed.manifest.dropped[0].item.retention,
        ContextRetention::Recoverable
    );
    let model_text = joined_model_text(&governed.model_input);
    assert!(!model_text.contains("RECOVERABLE_LOG"));
}

fn function_call_item(call_id: &str) -> ResponseItem {
    ResponseItem::FunctionCall {
        id: None,
        name: "shell_command".to_string(),
        namespace: None,
        arguments: "{}".to_string(),
        call_id: call_id.to_string(),
        internal_chat_message_metadata_passthrough: None,
    }
}

fn function_output_item(call_id: &str, output: &str) -> ResponseItem {
    ResponseItem::FunctionCallOutput {
        id: None,
        call_id: call_id.to_string(),
        output: FunctionCallOutputPayload::from_text(output.to_string()),
        internal_chat_message_metadata_passthrough: None,
    }
}

fn text_message(role: &str, text: &str) -> ResponseItem {
    ResponseItem::Message {
        id: None,
        role: role.to_string(),
        content: vec![ContentItem::InputText {
            text: text.to_string(),
        }],
        phase: None,
        internal_chat_message_metadata_passthrough: None,
    }
}

fn joined_model_text(items: &[ResponseItem]) -> String {
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
