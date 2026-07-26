//! Session-wide mutable state.

use codex_protocol::models::AdditionalPermissionProfile;
use codex_protocol::models::ResponseItem;
use codex_sandboxing::policy_transforms::merge_permission_profiles;
use std::collections::HashMap;
use std::collections::HashSet;
use std::collections::VecDeque;
use std::ops::Deref;

use super::AdditionalContextStore;
use super::auto_compact_window::AutoCompactWindow;
use super::auto_compact_window::AutoCompactWindowIds;
use super::auto_compact_window::AutoCompactWindowSnapshot;
use crate::context_engine::GovernanceProjection;
use crate::context_engine::InMemoryEvidenceLedger;
use crate::context_engine::TaskState;
use crate::context_engine::TrajectoryError;
use crate::context_engine::TrajectoryLedger;
use crate::context_engine::deterministic_task_delta_for_response_item;
use crate::context_manager::ContextManager;
use crate::session::PreviousTurnSettings;
use crate::session::session::SessionConfiguration;
use crate::session::time_reminder::CurrentTimeReminderState;
use crate::session_startup_prewarm::SessionStartupPrewarmHandle;
use codex_protocol::protocol::RateLimitSnapshot;
use codex_protocol::protocol::RolloutItem;
use codex_protocol::protocol::TokenUsage;
use codex_protocol::protocol::TokenUsageInfo;
use codex_protocol::protocol::TurnContextItem;
use codex_utils_output_truncation::TruncationPolicy;

/// Persistent, session-scoped state previously stored directly on `Session`.
pub(crate) struct SessionState {
    pub(crate) session_configuration: SessionConfiguration,
    pub(crate) history: ContextManager,
    pub(crate) context_evidence: InMemoryEvidenceLedger,
    pub(crate) context_task_state: TaskState,
    pub(crate) trajectory_ledger: TrajectoryLedger,
    pending_tool_output_indices: Vec<u64>,
    observed_tool_output_indices: HashSet<u64>,
    pub(crate) latest_rate_limits: Option<RateLimitSnapshot>,
    pub(crate) server_reasoning_included: bool,
    pub(crate) mcp_dependency_prompted: HashSet<String>,
    pub(crate) additional_context: AdditionalContextStore,
    /// Settings used by the latest regular user turn, used for turn-to-turn
    /// model/realtime handling on subsequent regular turns (including full-context
    /// reinjection after resume or `/compact`).
    previous_turn_settings: Option<PreviousTurnSettings>,
    /// Runtime accounting state for the active auto-compaction window.
    auto_compact_window: AutoCompactWindow,
    /// Startup prewarmed session prepared during session initialization.
    pub(crate) startup_prewarm: Option<SessionStartupPrewarmHandle>,
    pub(crate) current_time_reminder: CurrentTimeReminderState,
    pub(crate) active_connector_selection: HashSet<String>,
    pub(crate) pending_session_start_sources: VecDeque<codex_hooks::SessionStartSource>,
    granted_permissions_by_environment_id: HashMap<String, AdditionalPermissionProfile>,
    next_turn_is_first: bool,
}

impl SessionState {
    /// Create a new session state mirroring previous `State::default()` semantics.
    #[cfg(test)]
    pub(crate) fn new(session_configuration: SessionConfiguration) -> Self {
        Self::new_with_auto_compact_window_ids(
            session_configuration,
            AutoCompactWindowIds::new_initial(),
        )
    }

    pub(crate) fn new_with_auto_compact_window_ids(
        session_configuration: SessionConfiguration,
        auto_compact_window_ids: AutoCompactWindowIds,
    ) -> Self {
        let history = ContextManager::new();
        Self {
            session_configuration,
            history,
            context_evidence: InMemoryEvidenceLedger::new(),
            context_task_state: TaskState::default(),
            trajectory_ledger: TrajectoryLedger::default(),
            pending_tool_output_indices: Vec::new(),
            observed_tool_output_indices: HashSet::new(),
            latest_rate_limits: None,
            server_reasoning_included: false,
            mcp_dependency_prompted: HashSet::new(),
            additional_context: AdditionalContextStore::default(),
            previous_turn_settings: None,
            auto_compact_window: AutoCompactWindow::new_with_ids(auto_compact_window_ids),
            startup_prewarm: None,
            current_time_reminder: CurrentTimeReminderState::default(),
            active_connector_selection: HashSet::new(),
            pending_session_start_sources: VecDeque::new(),
            granted_permissions_by_environment_id: HashMap::new(),
            next_turn_is_first: true,
        }
    }

    // History helpers
    pub(crate) fn record_items<I>(&mut self, items: I, policy: TruncationPolicy)
    where
        I: IntoIterator,
        I::Item: std::ops::Deref<Target = ResponseItem>,
    {
        for item in items {
            let item = item.deref();
            self.record_context_evidence_and_delta(item);
            self.history.record_items(std::iter::once(item), policy);
        }
    }

    pub(crate) fn context_evidence_refs(&self) -> HashMap<String, String> {
        self.context_evidence.evidence_refs_by_response_item_id()
    }

    pub(crate) fn context_evidence_indices(&self) -> HashMap<String, u64> {
        self.context_evidence.evidence_indices_by_response_item_id()
    }

    pub(crate) fn observed_tool_output_indices(&self) -> HashSet<u64> {
        self.observed_tool_output_indices.clone()
    }

    pub(crate) fn tool_output_by_index(&self, index: u64) -> Option<ResponseItem> {
        if !self.observed_tool_output_indices.contains(&index) {
            return None;
        }
        self.context_evidence.tool_output_by_index(index)
    }

    pub(crate) fn mark_pending_tool_outputs_observed(&mut self) {
        self.observed_tool_output_indices
            .extend(self.pending_tool_output_indices.drain(..));
    }

    pub(crate) fn context_task_state(&self) -> TaskState {
        self.context_task_state.clone()
    }

    pub(crate) fn append_trajectory_node(
        &mut self,
        summary: String,
        core_start: u64,
        core_end: u64,
        retrieval_start: u64,
        retrieval_end: u64,
        checkpoint: u64,
    ) -> Result<u64, TrajectoryError> {
        self.trajectory_ledger.append_node(
            summary,
            core_start,
            core_end,
            retrieval_start,
            retrieval_end,
            checkpoint,
        )
    }

    pub(crate) fn history_slice_page(
        &self,
        index: u64,
        before: usize,
        after: usize,
        offset: usize,
        max_chars: usize,
    ) -> Result<crate::context_engine::HistorySlicePage, TrajectoryError> {
        self.trajectory_ledger
            .history_slice_page(index, before, after, offset, max_chars)
    }

    pub(crate) fn trajectory_event_count(&self) -> u64 {
        self.trajectory_ledger.event_count()
    }

    pub(crate) fn set_governance_projection(&mut self, projection: GovernanceProjection) {
        self.trajectory_ledger.set_governance_projection(projection);
    }

    pub(crate) fn governance_projection(&self) -> Option<GovernanceProjection> {
        self.trajectory_ledger.governance_projection().cloned()
    }

    pub(crate) fn trajectory_nodes(&self) -> Vec<crate::context_engine::TrajectoryNode> {
        self.trajectory_ledger.nodes().to_vec()
    }

    pub(crate) fn rebuild_context_evidence_from_rollout(&mut self, items: &[RolloutItem]) {
        self.pending_tool_output_indices.clear();
        self.observed_tool_output_indices.clear();
        for item in items {
            match item {
                RolloutItem::ResponseItem(response_item) => {
                    self.record_replayed_context_evidence(response_item);
                }
                RolloutItem::InterAgentCommunication(communication) => {
                    self.record_replayed_context_evidence(&communication.to_model_input_item());
                }
                RolloutItem::Compacted(compacted) => {
                    for response_item in compacted.replacement_history.iter().flatten() {
                        self.record_replayed_context_evidence(response_item);
                    }
                }
                RolloutItem::SessionMeta(_)
                | RolloutItem::TurnContext(_)
                | RolloutItem::EventMsg(_) => {}
            }
        }
    }

    pub(crate) fn previous_turn_settings(&self) -> Option<PreviousTurnSettings> {
        self.previous_turn_settings.clone()
    }
    pub(crate) fn set_previous_turn_settings(
        &mut self,
        previous_turn_settings: Option<PreviousTurnSettings>,
    ) {
        self.previous_turn_settings = previous_turn_settings;
    }

    pub(crate) fn set_next_turn_is_first(&mut self, value: bool) {
        self.next_turn_is_first = value;
    }

    pub(crate) fn take_next_turn_is_first(&mut self) -> bool {
        let is_first_turn = self.next_turn_is_first;
        self.next_turn_is_first = false;
        is_first_turn
    }

    pub(crate) fn clone_history(&self) -> ContextManager {
        self.history.clone()
    }

    pub(crate) fn replace_history(
        &mut self,
        items: Vec<ResponseItem>,
        reference_context_item: Option<TurnContextItem>,
    ) {
        for item in &items {
            self.record_context_evidence_and_delta(item);
        }
        self.history.replace(items);
        self.history
            .set_reference_context_item(reference_context_item);
        self.auto_compact_window.clear_prefill();
    }

    fn record_context_evidence_and_delta(&mut self, item: &ResponseItem) {
        self.trajectory_ledger.record_event(item);
        let evidence_id = self.context_evidence.record_response_item(item);
        self.track_pending_tool_output(evidence_id.as_u64(), item);
        let Some(delta) = deterministic_task_delta_for_response_item(evidence_id, item) else {
            return;
        };
        if let Some(applied_delta) = self.context_task_state.apply(delta) {
            self.context_evidence
                .record_delta(applied_delta.to_delta_note_input());
        }
    }

    fn record_replayed_context_evidence(&mut self, item: &ResponseItem) {
        self.trajectory_ledger.record_event(item);
        let evidence_id = self.context_evidence.record_response_item(item);
        self.track_pending_tool_output(evidence_id.as_u64(), item);
        if is_model_generated_response_item(item) {
            self.mark_pending_tool_outputs_observed();
        }
    }

    fn track_pending_tool_output(&mut self, evidence_id: u64, item: &ResponseItem) {
        if matches!(
            item,
            ResponseItem::FunctionCallOutput { .. }
                | ResponseItem::CustomToolCallOutput { .. }
                | ResponseItem::ToolSearchOutput { .. }
        ) && !self.observed_tool_output_indices.contains(&evidence_id)
            && !self.pending_tool_output_indices.contains(&evidence_id)
        {
            self.pending_tool_output_indices.push(evidence_id);
        }
    }

    pub(crate) fn set_token_info(&mut self, info: Option<TokenUsageInfo>) {
        self.history.set_token_info(info);
    }

    pub(crate) fn set_reference_context_item(&mut self, item: Option<TurnContextItem>) {
        self.history.set_reference_context_item(item);
    }

    pub(crate) fn reference_context_item(&self) -> Option<TurnContextItem> {
        self.history.reference_context_item()
    }

    // Token/rate limit helpers
    pub(crate) fn update_token_info_from_usage(
        &mut self,
        usage: &TokenUsage,
        model_context_window: Option<i64>,
    ) {
        self.history.update_token_info(usage, model_context_window);
    }

    pub(crate) fn ensure_auto_compact_window_server_prefill_from_usage(
        &mut self,
        usage: &TokenUsage,
    ) {
        self.auto_compact_window
            .ensure_server_observed_prefill_from_usage(usage);
    }

    pub(crate) fn set_auto_compact_window_estimated_prefill(&mut self, tokens: i64) {
        self.auto_compact_window.set_estimated_prefill(tokens);
    }

    pub(crate) fn auto_compact_window_snapshot(&self) -> AutoCompactWindowSnapshot {
        self.auto_compact_window.snapshot()
    }

    pub(crate) fn claim_token_budget_reminder(&mut self) -> bool {
        self.auto_compact_window.claim_token_budget_reminder()
    }

    pub(crate) fn auto_compact_window_number(&self) -> u64 {
        self.auto_compact_window.window_number()
    }

    pub(crate) fn auto_compact_window_ids(&self) -> AutoCompactWindowIds {
        self.auto_compact_window.ids()
    }

    pub(crate) fn restore_auto_compact_window(
        &mut self,
        window_number: u64,
        ids: AutoCompactWindowIds,
    ) {
        self.auto_compact_window.restore(window_number, ids);
    }

    pub(crate) fn advance_auto_compact_window(&mut self) -> (u64, AutoCompactWindowIds) {
        self.auto_compact_window.advance()
    }

    pub(crate) fn request_new_context_window(&mut self) {
        self.auto_compact_window.request_new_context_window();
    }

    pub(crate) fn take_new_context_window_request(&mut self) -> bool {
        self.auto_compact_window.take_new_context_window_request()
    }

    pub(crate) fn start_new_context_window(&mut self) -> (u64, AutoCompactWindowIds) {
        let window = self.auto_compact_window.advance();
        self.auto_compact_window.clear_prefill();
        window
    }

    pub(crate) fn token_info(&self) -> Option<TokenUsageInfo> {
        self.history.token_info()
    }

    pub(crate) fn set_rate_limits(&mut self, snapshot: RateLimitSnapshot) {
        self.latest_rate_limits = Some(merge_rate_limit_fields(
            self.latest_rate_limits.as_ref(),
            snapshot,
        ));
    }

    pub(crate) fn token_info_and_rate_limits(
        &self,
    ) -> (Option<TokenUsageInfo>, Option<RateLimitSnapshot>) {
        (self.token_info(), self.latest_rate_limits.clone())
    }

    pub(crate) fn set_token_usage_full(&mut self, context_window: i64) {
        self.history.set_token_usage_full(context_window);
    }

    pub(crate) fn get_total_token_usage(&self, server_reasoning_included: bool) -> i64 {
        self.history
            .get_total_token_usage(server_reasoning_included)
    }

    pub(crate) fn set_server_reasoning_included(&mut self, included: bool) {
        self.server_reasoning_included = included;
    }

    pub(crate) fn server_reasoning_included(&self) -> bool {
        self.server_reasoning_included
    }

    pub(crate) fn record_mcp_dependency_prompted<I>(&mut self, names: I)
    where
        I: IntoIterator<Item = String>,
    {
        self.mcp_dependency_prompted.extend(names);
    }

    pub(crate) fn mcp_dependency_prompted(&self) -> HashSet<String> {
        self.mcp_dependency_prompted.clone()
    }

    pub(crate) fn set_session_startup_prewarm(
        &mut self,
        startup_prewarm: SessionStartupPrewarmHandle,
    ) {
        self.startup_prewarm = Some(startup_prewarm);
    }

    pub(crate) fn take_session_startup_prewarm(&mut self) -> Option<SessionStartupPrewarmHandle> {
        self.startup_prewarm.take()
    }

    // Adds connector IDs to the active set and returns the merged selection.
    pub(crate) fn merge_connector_selection<I>(&mut self, connector_ids: I) -> HashSet<String>
    where
        I: IntoIterator<Item = String>,
    {
        self.active_connector_selection.extend(connector_ids);
        self.active_connector_selection.clone()
    }

    // Returns the current connector selection tracked on session state.
    pub(crate) fn get_connector_selection(&self) -> HashSet<String> {
        self.active_connector_selection.clone()
    }

    // Removes all currently tracked connector selections.
    pub(crate) fn clear_connector_selection(&mut self) {
        self.active_connector_selection.clear();
    }

    pub(crate) fn queue_pending_session_start_source(
        &mut self,
        value: codex_hooks::SessionStartSource,
    ) {
        self.pending_session_start_sources.push_back(value);
    }

    pub(crate) fn take_pending_session_start_source(
        &mut self,
    ) -> Option<codex_hooks::SessionStartSource> {
        self.pending_session_start_sources.pop_front()
    }

    pub(crate) fn record_granted_permissions(
        &mut self,
        environment_id: &str,
        permissions: AdditionalPermissionProfile,
    ) {
        let granted_permissions = merge_permission_profiles(
            self.granted_permissions_by_environment_id
                .get(environment_id),
            Some(&permissions),
        );
        if let Some(granted_permissions) = granted_permissions {
            self.granted_permissions_by_environment_id
                .insert(environment_id.to_string(), granted_permissions);
        }
    }

    pub(crate) fn granted_permissions(
        &self,
        environment_id: &str,
    ) -> Option<AdditionalPermissionProfile> {
        self.granted_permissions_by_environment_id
            .get(environment_id)
            .cloned()
    }
}

fn is_model_generated_response_item(item: &ResponseItem) -> bool {
    match item {
        ResponseItem::Message { role, .. } => role == "assistant",
        ResponseItem::Reasoning { .. }
        | ResponseItem::FunctionCall { .. }
        | ResponseItem::ToolSearchCall { .. }
        | ResponseItem::CustomToolCall { .. }
        | ResponseItem::WebSearchCall { .. }
        | ResponseItem::ImageGenerationCall { .. }
        | ResponseItem::AgentMessage { .. } => true,
        _ => false,
    }
}

// Sometimes new snapshots don't include credits or plan information.
// Preserve those from the previous snapshot when missing. For `limit_id`, treat
// missing values as the default `"codex"` bucket.
fn merge_rate_limit_fields(
    previous: Option<&RateLimitSnapshot>,
    mut snapshot: RateLimitSnapshot,
) -> RateLimitSnapshot {
    if snapshot.limit_id.is_none() {
        snapshot.limit_id = Some("codex".to_string());
    }
    if snapshot.credits.is_none() {
        snapshot.credits = previous.and_then(|prior| prior.credits.clone());
    }
    if snapshot.individual_limit.is_none() {
        snapshot.individual_limit = previous.and_then(|prior| prior.individual_limit.clone());
    }
    if snapshot.plan_type.is_none() {
        snapshot.plan_type = previous.and_then(|prior| prior.plan_type);
    }
    snapshot
}

#[cfg(test)]
#[path = "session_tests.rs"]
mod tests;
