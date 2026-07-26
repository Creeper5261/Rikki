use std::fs;
use std::path::Path;

use crate::function_tool::FunctionCallError;
use crate::tools::context::FunctionToolOutput;
use crate::tools::context::ToolInvocation;
use crate::tools::context::ToolOutput;
use crate::tools::context::ToolPayload;
use crate::tools::context::boxed_tool_output;
use crate::tools::handlers::parse_arguments;
use crate::tools::handlers::workspace_index_spec::SEARCH_WORKSPACE_FILES_TOOL_NAME;
use crate::tools::handlers::workspace_index_spec::create_search_workspace_files_tool;
use crate::tools::registry::CoreToolRuntime;
use crate::tools::registry::ToolExecutor;
use codex_protocol::models::ResponseInputItem;
use codex_tools::ToolName;
use codex_tools::ToolSpec;
use serde::Deserialize;
use serde_json::Value as JsonValue;
use serde_json::json;

const MAX_SCAN_ENTRIES: usize = 4_000;
const MAX_DEPTH: usize = 8;
const DEFAULT_MAX_RESULTS: usize = 24;
const HARD_MAX_RESULTS: usize = 64;
const IGNORED_DIRECTORY_NAMES: &[&str] = &[
    ".git",
    "node_modules",
    "target",
    "dist",
    "build",
    ".venv",
    "venv",
    "__pycache__",
];

#[derive(Debug, Deserialize)]
struct SearchWorkspaceFilesArgs {
    query: String,
    max_results: Option<usize>,
}

#[derive(Debug, Clone)]
struct SearchWorkspaceFilesOutput {
    value: JsonValue,
}

impl ToolOutput for SearchWorkspaceFilesOutput {
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

pub struct SearchWorkspaceFilesHandler;

impl ToolExecutor<ToolInvocation> for SearchWorkspaceFilesHandler {
    fn tool_name(&self) -> ToolName {
        ToolName::plain(SEARCH_WORKSPACE_FILES_TOOL_NAME)
    }

    fn spec(&self) -> ToolSpec {
        create_search_workspace_files_tool()
    }

    fn handle(&self, invocation: ToolInvocation) -> codex_tools::ToolExecutorFuture<'_> {
        Box::pin(async move {
            let ToolPayload::Function { arguments } = &invocation.payload else {
                return Err(FunctionCallError::RespondToModel(
                    "search_workspace_files handler received unsupported payload".to_string(),
                ));
            };
            let args: SearchWorkspaceFilesArgs = parse_arguments(arguments)?;
            let query = args.query.trim();
            if query.is_empty() {
                return Err(FunctionCallError::RespondToModel(
                    "query must not be empty".to_string(),
                ));
            }
            let max_results = args
                .max_results
                .unwrap_or(DEFAULT_MAX_RESULTS)
                .clamp(1, HARD_MAX_RESULTS);
            #[allow(deprecated)]
            let root = invocation.turn.cwd.as_path();
            let search = search_workspace_files(root, query, max_results).map_err(|error| {
                FunctionCallError::RespondToModel(format!("workspace index failed: {error}"))
            })?;
            let value = json!({
                "root": root.to_string_lossy(),
                "query": query,
                "matches": search.matches,
                "scanned_entries": search.scanned_entries,
                "truncated": search.truncated,
                "index_kind": "bounded_filename_index",
                "content_included": false,
            });
            Ok(boxed_tool_output(SearchWorkspaceFilesOutput { value }))
        })
    }
}

impl CoreToolRuntime for SearchWorkspaceFilesHandler {}

#[derive(Debug, PartialEq, Eq)]
struct WorkspaceSearchResult {
    matches: Vec<String>,
    scanned_entries: usize,
    truncated: bool,
}

fn search_workspace_files(
    root: &Path,
    query: &str,
    max_results: usize,
) -> std::io::Result<WorkspaceSearchResult> {
    let normalized_query = query.replace('\\', "/").to_ascii_lowercase();
    let mut pending = vec![(root.to_path_buf(), 0_usize)];
    let mut matches = Vec::new();
    let mut scanned_entries = 0;
    let mut truncated = false;

    while let Some((directory, depth)) = pending.pop() {
        if depth > MAX_DEPTH {
            truncated = true;
            continue;
        }
        let entries = match fs::read_dir(&directory) {
            Ok(entries) => entries,
            Err(_) => continue,
        };
        for entry in entries.flatten() {
            scanned_entries += 1;
            if scanned_entries > MAX_SCAN_ENTRIES {
                truncated = true;
                break;
            }
            let file_type = match entry.file_type() {
                Ok(file_type) => file_type,
                Err(_) => continue,
            };
            let name = entry.file_name();
            let name = name.to_string_lossy();
            if file_type.is_dir() {
                if !file_type.is_symlink() && !is_ignored_directory(&name) {
                    pending.push((entry.path(), depth + 1));
                }
                continue;
            }
            if !file_type.is_file() || file_type.is_symlink() {
                continue;
            }
            let path = entry.path();
            let relative = path.strip_prefix(root).unwrap_or(path.as_path());
            let relative = relative.to_string_lossy().replace('\\', "/");
            if relative.to_ascii_lowercase().contains(&normalized_query) {
                matches.push(relative);
            }
        }
        if truncated {
            break;
        }
    }
    matches.sort();
    matches.dedup();
    if matches.len() > max_results {
        matches.truncate(max_results);
        truncated = true;
    }
    Ok(WorkspaceSearchResult {
        matches,
        scanned_entries,
        truncated,
    })
}

fn is_ignored_directory(name: &str) -> bool {
    IGNORED_DIRECTORY_NAMES
        .iter()
        .any(|ignored| name.eq_ignore_ascii_case(ignored))
}

#[cfg(test)]
#[path = "workspace_index_tests.rs"]
mod tests;
