use std::collections::HashMap;
use std::collections::HashSet;
use std::fmt;

use codex_protocol::models::ContentItem;
use codex_protocol::models::ResponseItem;
use sha1::Digest;

mod task_state;
mod trajectory;

pub use task_state::ChangedFilesSnapshot;
pub use task_state::NavigationNodeDescription;
pub use task_state::RepositoryRevision;
pub use task_state::RequirementRevision;
pub use task_state::SubgoalPlanItem;
pub use task_state::SubgoalPlanStatus;
pub use task_state::SubgoalPlanUpdate;
pub use task_state::SubgoalRecord;
pub use task_state::SubgoalStatus;
pub use task_state::SubgoalVerificationRequirement;
pub use task_state::TaskDelta;
pub use task_state::TaskState;
pub use task_state::ToolCallRecord;
pub use task_state::ToolOutcomeCategory;
pub use task_state::ToolOutcomeRecord;
pub use task_state::ToolOutcomeResolution;
pub use task_state::ToolOutcomeStatus;
pub use task_state::VerificationRecord;
pub use task_state::deterministic_task_delta_for_response_item;
pub use trajectory::GovernanceProjection;
pub use trajectory::HistorySlicePage;
pub use trajectory::TrajectoryError;
pub use trajectory::TrajectoryLedger;
pub use trajectory::TrajectoryNode;

const MODEL_GOVERNANCE_NOTE_LIST_LIMIT: usize = 12;
const MODEL_GOVERNANCE_NOTE_REF_CHAR_LIMIT: usize = 120;
pub const MODEL_GOVERNANCE_NOTE_TOKEN_RESERVE: u32 = 1_280;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct EvidenceId(u64);

impl EvidenceId {
    pub fn as_u64(self) -> u64 {
        self.0
    }
}

impl fmt::Display for EvidenceId {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{}", self.0)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct DeltaId(u64);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EvidenceKind {
    ToolOutput,
    UserInput,
    ModelOutput,
    FileSnapshot,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EvidenceRecordInput {
    pub turn_id: String,
    pub step_index: u32,
    pub source_kind: EvidenceKind,
    pub raw_ref: String,
    pub content_hash: String,
    pub token_estimate: u32,
    pub related_response_item_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EvidenceRecord {
    pub id: EvidenceId,
    pub turn_id: String,
    pub step_index: u32,
    pub source_kind: EvidenceKind,
    pub raw_ref: String,
    pub content_hash: String,
    pub token_estimate: u32,
    pub related_response_item_id: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeltaKind {
    Correction,
    Refinement,
    Constraint,
    OpenQuestion,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DeltaNoteInput {
    pub evidence_ids: Vec<EvidenceId>,
    pub kind: DeltaKind,
    pub statement: String,
    pub invalidated_assumption: Option<String>,
    pub scope_paths: Vec<String>,
    pub confidence: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DeltaNote {
    pub id: DeltaId,
    pub evidence_ids: Vec<EvidenceId>,
    pub kind: DeltaKind,
    pub statement: String,
    pub invalidated_assumption: Option<String>,
    pub scope_paths: Vec<String>,
    pub confidence: f64,
}

#[derive(Debug, Default)]
pub struct InMemoryEvidenceLedger {
    next_evidence_id: u64,
    next_delta_id: u64,
    evidence: HashMap<EvidenceId, EvidenceRecord>,
    deltas: HashMap<DeltaId, DeltaNote>,
    deltas_by_evidence: HashMap<EvidenceId, Vec<DeltaId>>,
    evidence_by_response_item_id: HashMap<String, EvidenceId>,
    response_items: HashMap<EvidenceId, ResponseItem>,
}

impl InMemoryEvidenceLedger {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn record_evidence(&mut self, input: EvidenceRecordInput) -> EvidenceId {
        let id = EvidenceId(self.next_evidence_id);
        self.next_evidence_id += 1;

        if let Some(response_item_id) = input.related_response_item_id.as_ref() {
            self.evidence_by_response_item_id
                .insert(response_item_id.clone(), id);
        }

        self.evidence.insert(
            id,
            EvidenceRecord {
                id,
                turn_id: input.turn_id,
                step_index: input.step_index,
                source_kind: input.source_kind,
                raw_ref: input.raw_ref,
                content_hash: input.content_hash,
                token_estimate: input.token_estimate,
                related_response_item_id: input.related_response_item_id,
            },
        );

        id
    }

    pub fn record_response_item(&mut self, item: &ResponseItem) -> EvidenceId {
        let response_item_id = item.id().map(str::to_string);
        if let Some(evidence_id) = response_item_id
            .as_ref()
            .and_then(|item_id| self.evidence_by_response_item_id.get(item_id))
        {
            return *evidence_id;
        }

        let serialized = serde_json::to_vec(item).unwrap_or_default();
        let content_hash = format!("{:x}", sha1::Sha1::digest(&serialized));
        let raw_ref = response_item_id.as_ref().map_or_else(
            || format!("rollout://content/sha1/{content_hash}"),
            |item_id| format!("rollout://response-item/{item_id}"),
        );
        let token_estimate = u32::try_from(serialized.len().div_ceil(4)).unwrap_or(u32::MAX);

        let evidence_id = self.record_evidence(EvidenceRecordInput {
            turn_id: item.turn_id().unwrap_or_default().to_string(),
            step_index: u32::try_from(self.evidence.len()).unwrap_or(u32::MAX),
            source_kind: evidence_kind_for_response_item(item),
            raw_ref,
            content_hash,
            token_estimate: token_estimate.max(1),
            related_response_item_id: response_item_id,
        });
        self.response_items.insert(evidence_id, item.clone());
        evidence_id
    }

    pub fn record_delta(&mut self, input: DeltaNoteInput) -> DeltaId {
        let id = DeltaId(self.next_delta_id);
        self.next_delta_id += 1;

        for evidence_id in &input.evidence_ids {
            self.deltas_by_evidence
                .entry(*evidence_id)
                .or_default()
                .push(id);
        }

        self.deltas.insert(
            id,
            DeltaNote {
                id,
                evidence_ids: input.evidence_ids,
                kind: input.kind,
                statement: input.statement,
                invalidated_assumption: input.invalidated_assumption,
                scope_paths: input.scope_paths,
                confidence: input.confidence,
            },
        );

        id
    }

    pub fn evidence(&self, id: EvidenceId) -> Option<&EvidenceRecord> {
        self.evidence.get(&id)
    }

    pub fn delta(&self, id: DeltaId) -> Option<&DeltaNote> {
        self.deltas.get(&id)
    }

    pub fn deltas_for_evidence(&self, id: EvidenceId) -> Vec<DeltaId> {
        self.deltas_by_evidence
            .get(&id)
            .cloned()
            .unwrap_or_default()
    }

    pub fn evidence_for_response_item_id(&self, response_item_id: &str) -> Option<&EvidenceRecord> {
        self.evidence_by_response_item_id
            .get(response_item_id)
            .and_then(|id| self.evidence.get(id))
    }

    pub fn evidence_refs_by_response_item_id(&self) -> HashMap<String, String> {
        self.evidence_by_response_item_id
            .iter()
            .map(|(response_item_id, evidence_id)| {
                (
                    response_item_id.clone(),
                    format!("evidence://response-item/{response_item_id}?record={evidence_id}"),
                )
            })
            .collect()
    }

    pub fn tool_output_by_index(&self, index: u64) -> Option<ResponseItem> {
        let evidence_id = EvidenceId(index);
        self.evidence
            .get(&evidence_id)
            .filter(|record| record.source_kind == EvidenceKind::ToolOutput)?;
        self.response_items.get(&evidence_id).cloned()
    }

    pub fn evidence_indices_by_response_item_id(&self) -> HashMap<String, u64> {
        self.evidence_by_response_item_id
            .iter()
            .map(|(response_item_id, evidence_id)| (response_item_id.clone(), evidence_id.as_u64()))
            .collect()
    }
}

/// Renders the only compact, model-visible representation of an already consumed tool output.
/// The renderer intentionally quotes only raw diagnostic/result lines: it never infers causes,
/// evaluates a patch, or proposes a follow-up action.
pub fn render_model_visible_tool_note(
    task_state: Option<&TaskState>,
    item: &ResponseItem,
    index: u64,
) -> Option<String> {
    let (tool, status, output) = match item {
        ResponseItem::FunctionCallOutput {
            call_id, output, ..
        }
        | ResponseItem::CustomToolCallOutput {
            call_id, output, ..
        } => {
            let outcome = task_state.and_then(|state| {
                state
                    .tool_outcomes()
                    .iter()
                    .find(|outcome| outcome.evidence_id.as_u64() == index)
            });
            let tool = outcome
                .and_then(|outcome| {
                    task_state
                        .and_then(|state| state.tool_calls().get(&outcome.call_id))
                        .map(command_from_tool_call)
                })
                .unwrap_or_else(|| format!("tool_call(call_id={call_id})"));
            let status = match outcome.map(|outcome| outcome.status) {
                Some(ToolOutcomeStatus::Succeeded) | None if output.success == Some(true) => {
                    "succeeded".to_string()
                }
                Some(ToolOutcomeStatus::Failed) | None if output.success == Some(false) => {
                    "failed".to_string()
                }
                _ => "incomplete".to_string(),
            };
            (tool, status, output.body.to_text().unwrap_or_default())
        }
        ResponseItem::ToolSearchOutput {
            status,
            execution,
            tools,
            ..
        } => (
            format!("tool_search execution={execution}"),
            tool_search_status(status),
            format!("tool search returned {} tool definition(s)", tools.len()),
        ),
        _ => return None,
    };

    let diagnostics = note_diagnostics(&output);
    let summary = note_summary(&output, &diagnostics);
    let mut lines = vec![format!("tool: {tool}"), format!("status: {status}")];
    if !diagnostics.is_empty() {
        lines.push(format!("diagnostic: {}", diagnostics.join("\n")));
    }
    lines.push(format!("summary: {summary}"));
    lines.push(format!("raw: get_tool_output(index={index})"));
    Some(lines.join("\n"))
}

fn command_from_tool_call(call: &ToolCallRecord) -> String {
    serde_json::from_str::<serde_json::Value>(&call.arguments)
        .ok()
        .and_then(|arguments| arguments.get("command")?.as_str().map(str::to_string))
        .unwrap_or_else(|| format!("{} {}", call.tool_name, call.arguments))
}

fn tool_search_status(status: &str) -> String {
    match status {
        "completed" | "succeeded" => "succeeded".to_string(),
        "failed" => "failed".to_string(),
        "cancelled" => "cancelled".to_string(),
        _ => "incomplete".to_string(),
    }
}

fn note_diagnostics(output: &str) -> Vec<String> {
    let mut diagnostics = Vec::new();
    let mut bytes = 0_usize;
    for line in output
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
    {
        let lower = line.to_ascii_lowercase();
        if !(lower.contains("error")
            || lower.contains("exception")
            || lower.contains("traceback")
            || lower.contains("failed"))
        {
            continue;
        }
        let line_bytes = line.len();
        if diagnostics.len() == 3 || bytes.saturating_add(line_bytes) > 1_024 {
            break;
        }
        diagnostics.push(line.to_string());
        bytes += line_bytes;
    }
    diagnostics
}

fn note_summary(output: &str, diagnostics: &[String]) -> String {
    let mut facts = Vec::new();
    for line in output
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
    {
        if diagnostics.iter().any(|diagnostic| diagnostic == line) {
            continue;
        }
        let lower = line.to_ascii_lowercase();
        if lower.contains("passed")
            || lower.contains("collected")
            || lower.contains("ran ")
            || lower.contains("completed")
            || lower.contains("written")
            || lower.contains("changed")
            || lower.contains("finished")
        {
            facts.push(line.to_string());
        }
        if facts.len() == 3 {
            break;
        }
    }
    if facts.is_empty() {
        if output.trim().is_empty() {
            "tool output contained no text".to_string()
        } else {
            "tool output contained text; use raw retrieval for the exact content".to_string()
        }
    } else {
        facts.join("; ")
    }
}

pub fn response_item_id_from_evidence_ref(evidence_ref: &str) -> Option<&str> {
    let value = evidence_ref.strip_prefix("evidence://response-item/")?;
    let response_item_id = value.split_once('?').map_or(value, |(item_id, _)| item_id);
    (!response_item_id.is_empty()).then_some(response_item_id)
}

fn evidence_kind_for_response_item(item: &ResponseItem) -> EvidenceKind {
    match item {
        ResponseItem::FunctionCallOutput { .. }
        | ResponseItem::CustomToolCallOutput { .. }
        | ResponseItem::ToolSearchOutput { .. } => EvidenceKind::ToolOutput,
        ResponseItem::Message { role, .. } if role == "user" || role == "developer" => {
            EvidenceKind::UserInput
        }
        ResponseItem::AdditionalTools { .. }
        | ResponseItem::Message { .. }
        | ResponseItem::Reasoning { .. }
        | ResponseItem::LocalShellCall { .. }
        | ResponseItem::FunctionCall { .. }
        | ResponseItem::ToolSearchCall { .. }
        | ResponseItem::CustomToolCall { .. }
        | ResponseItem::WebSearchCall { .. }
        | ResponseItem::ImageGenerationCall { .. }
        | ResponseItem::Compaction { .. }
        | ResponseItem::ContextCompaction { .. }
        | ResponseItem::AgentMessage { .. }
        | ResponseItem::CompactionTrigger { .. }
        | ResponseItem::Other => EvidenceKind::ModelOutput,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContextItemKind {
    UserRequirement,
    Delta,
    ToolOutput,
    GraphSignal,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum ContextRetention {
    Optional,
    Recoverable,
    Open,
    Required,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ContextItem {
    pub id: String,
    pub kind: ContextItemKind,
    pub token_estimate: u32,
    pub utility_score: f64,
    pub freshness: f64,
    pub retention: ContextRetention,
}

impl ContextItem {
    pub fn new(id: impl Into<String>, kind: ContextItemKind, token_estimate: u32) -> Self {
        Self {
            id: id.into(),
            kind,
            token_estimate,
            utility_score: 1.0,
            freshness: 1.0,
            retention: ContextRetention::Optional,
        }
    }

    pub fn with_utility_score(mut self, utility_score: f64) -> Self {
        self.utility_score = utility_score;
        self
    }

    pub fn with_freshness(mut self, freshness: f64) -> Self {
        self.freshness = freshness;
        self
    }

    pub fn with_retention(mut self, retention: ContextRetention) -> Self {
        self.retention = retention;
        self
    }

    fn weighted_utility(&self) -> f64 {
        self.utility_score * self.freshness
    }

    fn density(&self) -> f64 {
        if self.token_estimate == 0 {
            return self.weighted_utility();
        }

        self.weighted_utility() / f64::from(self.token_estimate)
    }

    fn retention_priority(&self) -> u8 {
        match self.retention {
            ContextRetention::Required | ContextRetention::Open => 2,
            ContextRetention::Optional => 1,
            ContextRetention::Recoverable => 0,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ContextBudget {
    pub max_tokens: u32,
    pub min_density: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContextDropReason {
    TokenBudgetExceeded,
    DensityBelowFloor,
    PairedToolCallNotSelected,
}

#[derive(Debug, Clone, Default)]
pub struct DensityPolicy;

impl DensityPolicy {
    pub fn select(&self, items: Vec<ContextItem>, budget: ContextBudget) -> ContextManifest {
        let selection = select_by_density(items, budget);

        ContextManifest {
            budget,
            selected: selection.selected,
            dropped: selection
                .dropped
                .into_iter()
                .map(|dropped| dropped.item)
                .collect(),
            total_selected_tokens: selection.total_selected_tokens,
            density_score: selection.density_score,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
struct DroppedSelection {
    item: ContextItem,
    reason: ContextDropReason,
}

#[derive(Debug, Clone, PartialEq)]
struct IndexedContextItem {
    index: usize,
    item: ContextItem,
}

#[derive(Debug, Clone, PartialEq)]
struct DensitySelection {
    selected: Vec<ContextItem>,
    dropped: Vec<DroppedSelection>,
    total_selected_tokens: u32,
    density_score: f64,
}

fn select_by_density(items: Vec<ContextItem>, budget: ContextBudget) -> DensitySelection {
    let mut ranked_items = items
        .into_iter()
        .enumerate()
        .map(|(index, item)| IndexedContextItem { index, item })
        .collect::<Vec<_>>();

    ranked_items.sort_by(|left, right| {
        right
            .item
            .retention_priority()
            .cmp(&left.item.retention_priority())
            .then_with(|| right.item.density().total_cmp(&left.item.density()))
            .then_with(|| left.item.id.cmp(&right.item.id))
            .then_with(|| left.index.cmp(&right.index))
    });

    let mut selected = Vec::new();
    let mut dropped = Vec::new();
    let mut total_selected_tokens: u32 = 0;
    let mut total_weighted_utility = 0.0;

    for indexed_item in ranked_items {
        let item = indexed_item.item;
        let selected_tokens_after_item = total_selected_tokens.checked_add(item.token_estimate);
        let fits_budget =
            selected_tokens_after_item.is_some_and(|tokens| tokens <= budget.max_tokens);
        let meets_density = item.density() >= budget.min_density;
        let must_keep = matches!(
            item.retention,
            ContextRetention::Open | ContextRetention::Required
        );

        if fits_budget && (must_keep || meets_density) {
            total_selected_tokens = selected_tokens_after_item.expect("fits budget implies sum");
            total_weighted_utility += item.weighted_utility();
            selected.push(IndexedContextItem {
                index: indexed_item.index,
                item,
            });
        } else {
            let reason = if !meets_density && !must_keep {
                ContextDropReason::DensityBelowFloor
            } else {
                ContextDropReason::TokenBudgetExceeded
            };
            dropped.push((indexed_item.index, DroppedSelection { item, reason }));
        }
    }

    let density_score = if total_selected_tokens == 0 {
        0.0
    } else {
        total_weighted_utility / f64::from(total_selected_tokens)
    };

    selected.sort_by_key(|item| item.index);
    dropped.sort_by_key(|(index, _)| *index);

    DensitySelection {
        selected: selected.into_iter().map(|item| item.item).collect(),
        dropped: dropped.into_iter().map(|(_, dropped)| dropped).collect(),
        total_selected_tokens,
        density_score,
    }
}

#[derive(Debug, Default)]
struct ToolCallPairCandidates {
    call_candidate_id: Option<String>,
    output_candidate_id: Option<String>,
}

fn close_tool_call_pairs(
    selection: DensitySelection,
    candidates: &[GovernedContextCandidate],
    max_tokens: u32,
) -> DensitySelection {
    let mut total_selected_tokens = selection.total_selected_tokens;
    let mut selected_ids = selection
        .selected
        .into_iter()
        .map(|item| item.id)
        .collect::<HashSet<_>>();
    let mut dropped_reasons = selection
        .dropped
        .into_iter()
        .map(|dropped| (dropped.item.id, dropped.reason))
        .collect::<HashMap<_, _>>();
    let mut pairs = HashMap::<String, ToolCallPairCandidates>::new();

    for candidate in candidates {
        let Some(model_item) = candidate.model_item.as_ref() else {
            continue;
        };
        let Some((pair_key, is_call)) = tool_call_pair_key(model_item) else {
            continue;
        };
        let pair = pairs.entry(pair_key).or_default();
        if is_call {
            pair.call_candidate_id = Some(candidate.item.id.clone());
        } else {
            pair.output_candidate_id = Some(candidate.item.id.clone());
        }
    }

    let pair_mates = pairs
        .values()
        .filter_map(|pair| {
            Some((
                pair.call_candidate_id.as_ref()?.clone(),
                pair.output_candidate_id.as_ref()?.clone(),
            ))
        })
        .flat_map(|(call_id, output_id)| {
            [(call_id.clone(), output_id.clone()), (output_id, call_id)]
        })
        .collect::<HashMap<_, _>>();
    let mut pairs = pairs.into_iter().collect::<Vec<_>>();
    pairs.sort_by(|left, right| left.0.cmp(&right.0));

    for (_, pair) in pairs {
        let (Some(call_candidate_id), Some(output_candidate_id)) =
            (pair.call_candidate_id, pair.output_candidate_id)
        else {
            continue;
        };
        let call_selected = selected_ids.contains(&call_candidate_id);
        let output_selected = selected_ids.contains(&output_candidate_id);
        if call_selected == output_selected {
            continue;
        }

        let selected_candidate_id = if call_selected {
            &call_candidate_id
        } else {
            &output_candidate_id
        };
        let missing_candidate_id = if call_selected {
            &output_candidate_id
        } else {
            &call_candidate_id
        };
        let selected_is_retained = candidates.iter().any(|candidate| {
            candidate.item.id == *selected_candidate_id
                && matches!(
                    candidate.item.retention,
                    ContextRetention::Open | ContextRetention::Required
                )
        });
        let missing_tokens = candidates
            .iter()
            .find(|candidate| candidate.item.id == *missing_candidate_id)
            .map(|candidate| candidate.item.token_estimate)
            .unwrap_or(0);
        if selected_is_retained {
            let mut eviction_candidates = candidates
                .iter()
                .filter(|candidate| {
                    selected_ids.contains(&candidate.item.id)
                        && candidate.item.id != *selected_candidate_id
                        && !matches!(
                            candidate.item.retention,
                            ContextRetention::Open | ContextRetention::Required
                        )
                        && pair_mates
                            .get(&candidate.item.id)
                            .and_then(|mate_id| {
                                candidates.iter().find(|mate| mate.item.id == *mate_id)
                            })
                            .is_none_or(|mate| {
                                !matches!(
                                    mate.item.retention,
                                    ContextRetention::Open | ContextRetention::Required
                                )
                            })
                })
                .collect::<Vec<_>>();
            eviction_candidates.sort_by(|left, right| {
                left.item
                    .retention_priority()
                    .cmp(&right.item.retention_priority())
                    .then_with(|| left.item.density().total_cmp(&right.item.density()))
                    .then_with(|| left.item.id.cmp(&right.item.id))
            });

            for eviction in eviction_candidates {
                if total_selected_tokens
                    .checked_add(missing_tokens)
                    .is_some_and(|tokens| tokens <= max_tokens)
                {
                    break;
                }
                if selected_ids.remove(&eviction.item.id) {
                    total_selected_tokens =
                        total_selected_tokens.saturating_sub(eviction.item.token_estimate);
                    dropped_reasons.insert(
                        eviction.item.id.clone(),
                        ContextDropReason::TokenBudgetExceeded,
                    );
                }
                if let Some(mate_id) = pair_mates.get(&eviction.item.id)
                    && selected_ids.remove(mate_id)
                    && let Some(mate) = candidates
                        .iter()
                        .find(|candidate| candidate.item.id == *mate_id)
                {
                    total_selected_tokens =
                        total_selected_tokens.saturating_sub(mate.item.token_estimate);
                    dropped_reasons.insert(
                        mate.item.id.clone(),
                        ContextDropReason::PairedToolCallNotSelected,
                    );
                }
            }

            if total_selected_tokens
                .checked_add(missing_tokens)
                .is_some_and(|tokens| tokens <= max_tokens)
            {
                selected_ids.insert(missing_candidate_id.clone());
                dropped_reasons.remove(missing_candidate_id);
                total_selected_tokens += missing_tokens;
                continue;
            }
        }

        for candidate_id in [call_candidate_id, output_candidate_id] {
            if selected_ids.remove(&candidate_id) {
                dropped_reasons.insert(candidate_id, ContextDropReason::PairedToolCallNotSelected);
            }
        }
    }

    let selected = candidates
        .iter()
        .filter(|&candidate| selected_ids.contains(&candidate.item.id))
        .map(|candidate| candidate.item.clone())
        .collect::<Vec<_>>();
    let dropped = candidates
        .iter()
        .filter(|&candidate| !selected_ids.contains(&candidate.item.id))
        .map(|candidate| DroppedSelection {
            item: candidate.item.clone(),
            reason: *dropped_reasons
                .get(&candidate.item.id)
                .expect("each non-selected candidate must have a drop reason"),
        })
        .collect::<Vec<_>>();
    let total_selected_tokens = selected.iter().fold(0_u32, |total, item| {
        total.saturating_add(item.token_estimate)
    });
    let total_weighted_utility = selected
        .iter()
        .map(ContextItem::weighted_utility)
        .sum::<f64>();
    let density_score = if total_selected_tokens == 0 {
        0.0
    } else {
        total_weighted_utility / f64::from(total_selected_tokens)
    };

    DensitySelection {
        selected,
        dropped,
        total_selected_tokens,
        density_score,
    }
}

pub(crate) fn tool_call_pair_key(item: &ResponseItem) -> Option<(String, bool)> {
    match item {
        ResponseItem::FunctionCall { call_id, .. } => Some((format!("function:{call_id}"), true)),
        ResponseItem::FunctionCallOutput { call_id, .. } => {
            Some((format!("function:{call_id}"), false))
        }
        ResponseItem::CustomToolCall { call_id, .. } => Some((format!("custom:{call_id}"), true)),
        ResponseItem::CustomToolCallOutput { call_id, .. } => {
            Some((format!("custom:{call_id}"), false))
        }
        ResponseItem::ToolSearchCall {
            call_id: Some(call_id),
            ..
        } => Some((format!("tool_search:{call_id}"), true)),
        ResponseItem::ToolSearchOutput {
            call_id: Some(call_id),
            ..
        } => Some((format!("tool_search:{call_id}"), false)),
        _ => None,
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ContextManifest {
    pub budget: ContextBudget,
    pub selected: Vec<ContextItem>,
    pub dropped: Vec<ContextItem>,
    pub total_selected_tokens: u32,
    pub density_score: f64,
}

impl ContextManifest {
    pub fn selected_ids(&self) -> Vec<&str> {
        self.selected.iter().map(|item| item.id.as_str()).collect()
    }

    pub fn dropped_ids(&self) -> Vec<&str> {
        self.dropped.iter().map(|item| item.id.as_str()).collect()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GovernedContextSection {
    Manifest,
    SelectedContext,
    CurrentUserInput,
}

#[derive(Debug, Clone, PartialEq)]
pub struct GovernedContextCandidate {
    pub item: ContextItem,
    pub model_item: Option<ResponseItem>,
    pub source_ref: Option<String>,
}

impl GovernedContextCandidate {
    pub fn new(item: ContextItem, model_item: ResponseItem) -> Self {
        Self {
            item,
            model_item: Some(model_item),
            source_ref: None,
        }
    }

    pub fn deferred(item: ContextItem) -> Self {
        Self {
            item,
            model_item: None,
            source_ref: None,
        }
    }

    pub fn with_source_ref(mut self, source_ref: impl Into<String>) -> Self {
        self.source_ref = Some(source_ref.into());
        self
    }

    fn resolved_source_ref(&self) -> String {
        self.source_ref
            .clone()
            .unwrap_or_else(|| format!("context-item://{}", self.item.id))
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct GovernedContextRequest {
    pub candidates: Vec<GovernedContextCandidate>,
    pub graph_signals: Vec<GraphCandidateSet>,
    pub current_user_input: ResponseItem,
    pub budget: ContextBudget,
}

#[derive(Debug, Clone, PartialEq)]
pub struct GovernedContext {
    pub model_input: Vec<ResponseItem>,
    pub manifest: ContextGovernanceManifest,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ContextGovernanceManifest {
    pub budget: ContextBudget,
    pub selected: Vec<SelectedContextItem>,
    pub dropped: Vec<DroppedContextItem>,
    pub graph_refs: Vec<GovernedGraphRef>,
    pub task_state_tokens: u32,
    pub governance_note_tokens: u32,
    pub total_selected_tokens: u32,
    pub density_score: f64,
}

impl ContextGovernanceManifest {
    pub fn selected_ids(&self) -> Vec<&str> {
        self.selected
            .iter()
            .map(|item| item.item.id.as_str())
            .collect()
    }

    pub fn dropped_ids(&self) -> Vec<&str> {
        self.dropped
            .iter()
            .map(|item| item.item.id.as_str())
            .collect()
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct SelectedContextItem {
    pub item: ContextItem,
    pub source_ref: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DroppedContextItem {
    pub item: ContextItem,
    pub source_ref: String,
    pub reason: ContextDropReason,
}

#[derive(Debug, Clone, PartialEq)]
pub struct GovernedGraphRef {
    pub seed: GraphSeed,
    pub node_id: RepoNodeId,
    pub expandable_ref: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ContextGovernanceError {
    CurrentUserInputMissing,
    SelectedItemMissingPayload { selected_id: String },
    RetainedItemDropped { selected_id: String },
}

impl ContextGovernanceError {
    pub fn selected_id(&self) -> Option<&str> {
        match self {
            ContextGovernanceError::CurrentUserInputMissing => None,
            ContextGovernanceError::SelectedItemMissingPayload { selected_id } => {
                Some(selected_id.as_str())
            }
            ContextGovernanceError::RetainedItemDropped { selected_id } => {
                Some(selected_id.as_str())
            }
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct GovernedContextAssembler;

impl GovernedContextAssembler {
    pub fn assemble(
        &self,
        request: GovernedContextRequest,
    ) -> Result<GovernedContext, ContextGovernanceError> {
        let GovernedContextRequest {
            candidates,
            graph_signals,
            current_user_input,
            budget,
        } = request;

        let selection = close_tool_call_pairs(
            select_by_density(
                candidates
                    .iter()
                    .map(|candidate| candidate.item.clone())
                    .collect(),
                budget,
            ),
            &candidates,
            budget.max_tokens,
        );
        if let Some(dropped) = selection.dropped.iter().find(|dropped| {
            matches!(
                dropped.item.retention,
                ContextRetention::Open | ContextRetention::Required
            )
        }) {
            return Err(ContextGovernanceError::RetainedItemDropped {
                selected_id: dropped.item.id.clone(),
            });
        }
        let candidates_by_id: HashMap<&str, &GovernedContextCandidate> = candidates
            .iter()
            .map(|candidate| (candidate.item.id.as_str(), candidate))
            .collect();

        let mut selected = Vec::new();
        let mut selected_model_items = Vec::new();
        for item in selection.selected {
            let candidate = candidates_by_id
                .get(item.id.as_str())
                .expect("density selection should only return input candidates");
            let Some(model_item) = candidate.model_item.clone() else {
                return Err(ContextGovernanceError::SelectedItemMissingPayload {
                    selected_id: item.id.clone(),
                });
            };

            selected.push(SelectedContextItem {
                item,
                source_ref: candidate.resolved_source_ref(),
            });
            selected_model_items.push(model_item);
        }

        let dropped = selection
            .dropped
            .into_iter()
            .map(|dropped| {
                let candidate = candidates_by_id
                    .get(dropped.item.id.as_str())
                    .expect("density selection should only return input candidates");
                DroppedContextItem {
                    item: dropped.item,
                    source_ref: candidate.resolved_source_ref(),
                    reason: dropped.reason,
                }
            })
            .collect();

        let graph_refs = graph_signals
            .into_iter()
            .flat_map(|signal| {
                signal
                    .candidates
                    .into_iter()
                    .map(move |candidate| GovernedGraphRef {
                        seed: signal.seed.clone(),
                        node_id: candidate.node_id,
                        expandable_ref: candidate.expandable_ref,
                    })
            })
            .collect();

        let manifest = ContextGovernanceManifest {
            budget,
            selected,
            dropped,
            graph_refs,
            task_state_tokens: 0,
            governance_note_tokens: 0,
            total_selected_tokens: selection.total_selected_tokens,
            density_score: selection.density_score,
        };

        let mut model_input = Vec::with_capacity(selected_model_items.len() + 2);
        model_input.push(render_model_governance_note(&manifest));
        model_input.extend(selected_model_items);
        model_input.push(current_user_input);

        Ok(GovernedContext {
            model_input,
            manifest,
        })
    }
}

fn render_model_governance_note(manifest: &ContextGovernanceManifest) -> ResponseItem {
    let mut lines = vec![
        "CONTEXT GOVERNANCE NOTE".to_string(),
        format!(
            "context: selected_count={} deferred_count={} graph_ref_count={}",
            manifest.selected.len(),
            manifest.dropped.len(),
            manifest.graph_refs.len()
        ),
        "recovery_tool: get_tool_output(index, cursor?, max_chars?)".to_string(),
    ];
    lines.push("deferred_context:".to_string());
    if manifest.dropped.is_empty() {
        lines.push("- none".to_string());
    } else {
        lines.push(format!("- count={}", manifest.dropped.len()));
        lines.push("- recovery metadata is retained outside model context".to_string());
    }

    lines.push("graph_refs:".to_string());
    if manifest.graph_refs.is_empty() {
        lines.push("- none".to_string());
    } else {
        for graph_ref in manifest
            .graph_refs
            .iter()
            .take(MODEL_GOVERNANCE_NOTE_LIST_LIMIT)
        {
            lines.push(format!(
                "- expandable_ref={}",
                bounded_model_note_ref(&graph_ref.expandable_ref)
            ));
        }
        push_omitted_count(
            &mut lines,
            manifest.graph_refs.len(),
            MODEL_GOVERNANCE_NOTE_LIST_LIMIT,
        );
    }

    ResponseItem::Message {
        id: None,
        role: "developer".to_string(),
        content: vec![ContentItem::InputText {
            text: lines.join("\n"),
        }],
        phase: None,
        internal_chat_message_metadata_passthrough: None,
    }
}

fn push_omitted_count(lines: &mut Vec<String>, section_len: usize, rendered_limit: usize) {
    if section_len > rendered_limit {
        lines.push(format!("- omitted_count={}", section_len - rendered_limit));
    }
}

fn bounded_model_note_ref(value: &str) -> String {
    if value.chars().count() <= MODEL_GOVERNANCE_NOTE_REF_CHAR_LIMIT {
        return value.to_string();
    }
    value
        .chars()
        .take(MODEL_GOVERNANCE_NOTE_REF_CHAR_LIMIT)
        .collect::<String>()
        + "..."
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct RepoNodeId(u64);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RepoNodeKind {
    File,
    Symbol,
    Test,
    Module,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RepoEdgeKind {
    Imports,
    Defines,
    Calls,
    TestCovers,
    Mentions,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GraphSeed {
    Path(String),
    Symbol(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GraphBudget {
    pub max_candidates: usize,
    pub max_reason_edges: usize,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RepoReasonEdge {
    pub from: RepoNodeId,
    pub to: RepoNodeId,
    pub kind: RepoEdgeKind,
    pub confidence: f64,
    pub reason: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RepoGraphCandidate {
    pub node_id: RepoNodeId,
    pub reason_edges: Vec<RepoReasonEdge>,
    pub expandable_ref: String,
    pub content_preview: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct GraphCandidateSet {
    pub seed: GraphSeed,
    pub candidates: Vec<RepoGraphCandidate>,
}

#[derive(Debug, Clone)]
struct RepoNode {
    id: RepoNodeId,
    kind: RepoNodeKind,
    path: String,
    symbol: Option<String>,
}

#[derive(Debug, Clone)]
struct RepoEdge {
    from: RepoNodeId,
    to: RepoNodeId,
    kind: RepoEdgeKind,
    confidence: f64,
    reason: String,
}

#[derive(Debug, Default)]
pub struct InMemoryRepoGraph {
    next_node_id: u64,
    nodes: HashMap<RepoNodeId, RepoNode>,
    edges: Vec<RepoEdge>,
}

impl InMemoryRepoGraph {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn add_node(
        &mut self,
        kind: RepoNodeKind,
        path: impl Into<String>,
        symbol: Option<String>,
    ) -> RepoNodeId {
        let id = RepoNodeId(self.next_node_id);
        self.next_node_id += 1;

        self.nodes.insert(
            id,
            RepoNode {
                id,
                kind,
                path: path.into(),
                symbol,
            },
        );

        id
    }

    pub fn add_edge(
        &mut self,
        from: RepoNodeId,
        to: RepoNodeId,
        kind: RepoEdgeKind,
        confidence: f64,
        reason: impl Into<String>,
    ) {
        self.edges.push(RepoEdge {
            from,
            to,
            kind,
            confidence,
            reason: reason.into(),
        });
    }

    pub fn markov_blanket_candidates(
        &self,
        seed: GraphSeed,
        budget: GraphBudget,
    ) -> GraphCandidateSet {
        let Some(seed_node_id) = self.find_seed_node(&seed) else {
            return GraphCandidateSet {
                seed,
                candidates: Vec::new(),
            };
        };

        let mut candidates = Vec::new();

        for edge in &self.edges {
            let candidate_id = if edge.from == seed_node_id {
                edge.to
            } else if edge.to == seed_node_id {
                edge.from
            } else {
                continue;
            };

            if candidates.len() >= budget.max_candidates
                && !candidates
                    .iter()
                    .any(|candidate: &RepoGraphCandidate| candidate.node_id == candidate_id)
            {
                continue;
            }

            let reason_edge = RepoReasonEdge {
                from: edge.from,
                to: edge.to,
                kind: edge.kind,
                confidence: edge.confidence,
                reason: edge.reason.clone(),
            };

            if let Some(candidate) = candidates
                .iter_mut()
                .find(|candidate| candidate.node_id == candidate_id)
            {
                if candidate.reason_edges.len() < budget.max_reason_edges {
                    candidate.reason_edges.push(reason_edge);
                }
                continue;
            }

            let Some(node) = self.nodes.get(&candidate_id) else {
                continue;
            };

            candidates.push(RepoGraphCandidate {
                node_id: node.id,
                reason_edges: vec![reason_edge],
                expandable_ref: format!("repo-node://{}", node.path),
                content_preview: None,
            });
        }

        GraphCandidateSet { seed, candidates }
    }

    fn find_seed_node(&self, seed: &GraphSeed) -> Option<RepoNodeId> {
        match seed {
            GraphSeed::Path(path) => self
                .nodes
                .values()
                .find(|node| node.path == *path && node.kind != RepoNodeKind::Symbol)
                .map(|node| node.id),
            GraphSeed::Symbol(symbol) => self
                .nodes
                .values()
                .find(|node| node.symbol.as_ref() == Some(symbol))
                .map(|node| node.id),
        }
    }
}
