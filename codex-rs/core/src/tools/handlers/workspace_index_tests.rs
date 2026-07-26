use super::*;

#[test]
fn workspace_index_returns_sorted_relative_paths_and_skips_dependency_directories() {
    let workspace = tempfile::tempdir().expect("workspace tempdir");
    fs::create_dir_all(workspace.path().join("src")).expect("src directory");
    fs::create_dir_all(workspace.path().join("tests")).expect("tests directory");
    fs::create_dir_all(workspace.path().join("node_modules").join("pkg"))
        .expect("dependency directory");
    fs::write(workspace.path().join("src/loader.rs"), "fn loader() {}").expect("source fixture");
    fs::write(workspace.path().join("tests/loader_test.rs"), "").expect("test fixture");
    fs::write(workspace.path().join("node_modules/pkg/loader.rs"), "").expect("ignored fixture");

    let result = search_workspace_files(workspace.path(), "loader", 24).expect("index search");

    assert_eq!(
        result.matches,
        vec![
            "src/loader.rs".to_string(),
            "tests/loader_test.rs".to_string()
        ]
    );
    assert!(!result.truncated);
}

#[test]
fn workspace_index_limits_matches_without_returning_content() {
    let workspace = tempfile::tempdir().expect("workspace tempdir");
    for index in 0..3 {
        fs::write(
            workspace.path().join(format!("match-{index}.rs")),
            "secret source",
        )
        .expect("fixture file");
    }

    let result = search_workspace_files(workspace.path(), "match", 2).expect("index search");

    assert_eq!(result.matches.len(), 2);
    assert!(result.truncated);
    assert!(
        result
            .matches
            .iter()
            .all(|path| !path.contains("secret source"))
    );
}
