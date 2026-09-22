#[test]
fn probe_self_test_passes_and_reports_scope() {
    let text = aap_jni::probe();
    let lines: Vec<&str> = text.lines().collect();
    for expected in [
        "self_test=pass",
        "last_completed_stage=version",
        "response_version=1.5",
        "final_category=STAGE_LIMIT",
    ] {
        assert!(lines.contains(&expected), "missing {expected} in\n{text}");
    }
    assert!(
        lines
            .iter()
            .any(|l| l.starts_with("tls_library=OpenSSL 3.5.8 ")),
        "{text}"
    );
    assert!(text.is_ascii());
    assert!(!text.contains('\0'));
}
