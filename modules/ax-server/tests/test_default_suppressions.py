"""BUG #28 -- the shipped default suppression list.

    "28% of the trial's candidates (5 of 18) were undeletable compiler/Lombok
    output: enum `values()`/`valueOf()`, and `equals`/`hashCode`/`toString` on
    `@Data` classes. Suppressing those by default is a free precision win."

Free, but not silent. The requirement that shapes this whole file is that the
defaults be VISIBLE, ATTRIBUTABLE and OVERRIDABLE -- "a silent invisible filter
is exactly the kind of thing this project keeps finding bugs in" -- so there is
a test for each:

  * they apply (the five trial categories, by exact descriptor);
  * they are listable (`--list-suppressions`, `/v1/suppressions`, `gt_suppressions`);
  * every match names the list it came from in the verdict's `reasons`;
  * `--no-default-suppressions` turns the whole set off;
  * a suppressed method is still UNKNOWN and still in the output, never omitted.
"""

import json
import urllib.request

import pytest

from ax_server.analysis.engine import AnalysisConfig, AnalysisEngine
from ax_server.analysis.manifest import load_manifest
from ax_server.analysis.models import DEAD_CANDIDATE, UNKNOWN, MethodRef
from ax_server.analysis.proposals import SqliteProposalLedger
from ax_server.analysis.suppression import (
    DEFAULT_SOURCE,
    MANIFEST_SOURCE,
    ORIGIN_DEFAULT,
    ORIGIN_USER,
    Suppressions,
    compose_suppressions,
    default_suppressions,
    manifest_suppressions,
)
from ax_server.api.service import QueryService

from conftest import BUILD_SHA, FIXTURES, realistic_payload

# The five categories the trial actually hit, as javac and Lombok emit them.
ENUM = "com.acme.order.Status"
DATA = "com.acme.order.OrderDto"


def ref(cls: str, name: str, desc: str) -> MethodRef:
    return MethodRef(cls, name, desc)


# ======================================================================
# 1. the defaults apply
# ======================================================================


@pytest.mark.parametrize(
    ("cls", "name", "desc"),
    [
        # enum members javac synthesises
        (ENUM, "values", "()[Lcom/acme/order/Status;"),
        (ENUM, "valueOf", "(Ljava/lang/String;)Lcom/acme/order/Status;"),
        # Object overrides Lombok writes for @Data
        (DATA, "equals", "(Ljava/lang/Object;)Z"),
        (DATA, "hashCode", "()I"),
        (DATA, "toString", "()Ljava/lang/String;"),
        (DATA, "canEqual", "(Ljava/lang/Object;)Z"),
        # @Builder
        (DATA, "builder", "()Lcom/acme/order/OrderDto$OrderDtoBuilder;"),
        (DATA, "toBuilder", "()Lcom/acme/order/OrderDto$OrderDtoBuilder;"),
        ("com.acme.order.OrderDto$OrderDtoBuilder", "build", "()Lcom/acme/order/OrderDto;"),
    ],
)
def test_the_trial_categories_are_suppressed_by_default(cls, name, desc):
    rule = default_suppressions().match(ref(cls, name, desc))
    assert rule is not None, f"{cls}#{name}{desc} is still a candidate"
    assert rule.origin == ORIGIN_DEFAULT
    assert rule.source == DEFAULT_SOURCE


@pytest.mark.parametrize(
    ("cls", "name", "desc"),
    [
        # Hand-written code with a colliding NAME but a different descriptor.
        # The rules are exact on purpose: over-suppression only ever produces
        # an UNKNOWN, but an UNKNOWN nobody asked for is a lost candidate, and
        # lost candidates are this product's whole failure mode.
        (DATA, "toString", "(Ljava/util/Locale;)Ljava/lang/String;"),
        (DATA, "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z"),
        (DATA, "hashCode", "(Ljava/lang/Object;)I"),
        (DATA, "values", "()Ljava/util/List;"),
        (DATA, "valueOf", "(I)Lcom/acme/order/OrderDto;"),
        (DATA, "builder", "(Ljava/lang/String;)Lcom/acme/B;"),
        # And ordinary business methods, obviously.
        ("com.acme.shipping.RateSelector", "pick", "(Ljava/util/List;)Lcom/acme/Rate;"),
    ],
)
def test_hand_written_lookalikes_are_not_suppressed(cls, name, desc):
    assert default_suppressions().match(ref(cls, name, desc)) is None


def test_the_default_list_is_contracts_5_format():
    """Not a hardcoded predicate: a rule list the user can read and argue with."""
    rules = list(default_suppressions())
    assert len(rules) >= 9
    # Every rule round-trips through the CONTRACTS 5 pattern syntax.
    for rule in rules:
        assert Suppressions.from_text(rule.pattern).rules[0].pattern == rule.pattern
        assert rule.comment, f"{rule.pattern} ships without an explanation"


# ======================================================================
# 2. the two categories a glob cannot express, from the manifest
# ======================================================================


GENERATED_MANIFEST = {
    "schemaVersion": 2,
    "buildSha": BUILD_SHA,
    "artifact": "checkout-service",
    "classes": [
        {
            "name": "com.acme.gen.MapperImpl",
            "annotations": ["javax.annotation.processing.Generated"],
            "methods": [
                {"idx": 0, "name": "map", "desc": "(Lcom/acme/A;)Lcom/acme/B;",
                 "dynamicallyObservable": True, "shortCircuitable": False}
            ],
        },
        {
            "name": "com.acme.order.Money",
            "isRecord": True,
            "recordComponents": ["amount", {"name": "currency"}],
            "methods": [
                {"idx": 0, "name": "amount", "desc": "()J",
                 "dynamicallyObservable": True, "shortCircuitable": False},
                {"idx": 1, "name": "currency", "desc": "()Ljava/lang/String;",
                 "dynamicallyObservable": True, "shortCircuitable": False},
                {"idx": 2, "name": "convert", "desc": "(Ljava/lang/String;)Lcom/acme/Money;",
                 "dynamicallyObservable": True, "shortCircuitable": False},
            ],
        },
        {
            "name": "com.acme.order.Handler",
            "methods": [
                {"idx": 0, "name": "handle", "desc": "()V", "annotations": ["@lombok.Generated"],
                 "dynamicallyObservable": True, "shortCircuitable": False},
                {"idx": 1, "name": "real", "desc": "()V",
                 "dynamicallyObservable": True, "shortCircuitable": False},
            ],
        },
    ],
}


@pytest.fixture
def generated_manifest():
    return load_manifest(GENERATED_MANIFEST)


def test_generated_annotated_members_are_suppressed(generated_manifest):
    rules = manifest_suppressions(generated_manifest)
    # A @Generated CLASS covers all of its members with one rule.
    cls_rule = rules.match(ref("com.acme.gen.MapperImpl", "map", "(Lcom/acme/A;)Lcom/acme/B;"))
    assert cls_rule is not None and cls_rule.origin == ORIGIN_DEFAULT
    assert "@Generated class" in cls_rule.comment
    # A @Generated MEMBER on an ordinary class is matched on its own.
    member = rules.match(ref("com.acme.order.Handler", "handle", "()V"))
    assert member is not None and "@Generated member" in member.comment
    # Its sibling is untouched.
    assert rules.match(ref("com.acme.order.Handler", "real", "()V")) is None


def test_record_accessors_are_suppressed_but_record_methods_are_not(generated_manifest):
    rules = manifest_suppressions(generated_manifest)
    amount = rules.match(ref("com.acme.order.Money", "amount", "()J"))
    assert amount is not None
    assert "record accessor" in amount.comment
    assert rules.match(ref("com.acme.order.Money", "currency", "()Ljava/lang/String;")) is not None
    # A real method on the record is still a candidate.
    assert rules.match(
        ref("com.acme.order.Money", "convert", "(Ljava/lang/String;)Lcom/acme/Money;")
    ) is None


def test_the_annotation_is_matched_on_its_simple_name(generated_manifest):
    """`@Generated` ships under four packages; pinning one misses three."""
    for spelling in (
        "javax.annotation.Generated",
        "jakarta.annotation.Generated",
        "lombok.Generated",
        "@Generated",
        "Ljavax/annotation/processing/Generated;",
    ):
        manifest = load_manifest(
            {
                "buildSha": BUILD_SHA,
                "classes": [
                    {
                        "name": "com.acme.X",
                        "methods": [
                            {"idx": 0, "name": "m", "desc": "()V", "annotations": [spelling],
                             "dynamicallyObservable": True, "shortCircuitable": False}
                        ],
                    }
                ],
            }
        )
        assert manifest_suppressions(manifest).match(ref("com.acme.X", "m", "()V")) is not None, (
            spelling
        )


def test_a_manifest_without_the_additive_fields_generates_nothing(manifest):
    """CONTRACTS 1: an absent field defaults to its SAFE direction. For a
    suppression the safe direction is "do not hide anything"."""
    assert len(manifest_suppressions(manifest)) == 0


def test_junk_in_the_additive_fields_is_not_a_manifest_error():
    """A suppression HINT is not worth refusing a manifest over (bug #22's
    lesson: a strict reader that rejects the real producer is 100% data loss)."""
    manifest = load_manifest(
        {
            "buildSha": BUILD_SHA,
            "classes": [
                {
                    "name": "com.acme.X",
                    "annotations": "not-a-list",
                    "recordComponents": 7,
                    "isRecord": True,
                    "methods": [
                        {"idx": 0, "name": "m", "desc": "()V", "annotations": {"a": 1},
                         "dynamicallyObservable": True, "shortCircuitable": False}
                    ],
                }
            ],
        }
    )
    assert len(manifest_suppressions(manifest)) == 0


# ======================================================================
# 3. they are LISTABLE
# ======================================================================


def test_the_composed_set_lists_both_layers(manifest, suppressions):
    composed = compose_suppressions(suppressions, manifest=manifest)
    counts = composed.counts_by_origin()
    assert counts[ORIGIN_USER] == 2
    assert counts[ORIGIN_DEFAULT] >= 9

    listing = composed.listing()
    assert len(listing) == len(composed)
    # User rules first: first-match-wins, so they own the attribution.
    assert [row["origin"] for row in listing[:2]] == [ORIGIN_USER, ORIGIN_USER]
    assert {row["origin"] for row in listing[2:]} == {ORIGIN_DEFAULT}
    assert all({"pattern", "origin", "source", "line", "comment"} <= set(row) for row in listing)


def test_the_cli_renders_them(manifest, suppressions):
    text = compose_suppressions(suppressions, manifest=manifest).render()
    assert "suppression rule(s)" in text
    assert "[default]" in text
    assert "[user   ]" in text
    assert "*#hashCode()I" in text
    assert DEFAULT_SOURCE in text


def test_list_suppressions_exits_after_printing(capsys, tmp_path):
    from ax_server.__main__ import main

    code = main(
        [
            "--manifest", str(FIXTURES / "manifest.json"),
            "--suppress", str(FIXTURES / "suppress.txt"),
            "--db", str(tmp_path / "unused.sqlite"),
            "--list-suppressions",
        ]
    )
    assert code == 0
    out = capsys.readouterr().out
    assert "*#values()[L*;" in out
    assert "com.acme.emergency.KillSwitch#*" in out
    # It exited before opening the database or binding a port.
    assert not (tmp_path / "unused.sqlite").exists()


def test_list_suppressions_honours_no_default_suppressions(capsys, tmp_path):
    from ax_server.__main__ import main

    main(
        [
            "--manifest", str(FIXTURES / "manifest.json"),
            "--suppress", str(FIXTURES / "suppress.txt"),
            "--db", str(tmp_path / "unused.sqlite"),
            "--no-default-suppressions",
            "--list-suppressions",
        ]
    )
    out = capsys.readouterr().out
    assert "*#hashCode()I" not in out
    assert "com.acme.emergency.KillSwitch#*" in out


def test_the_api_lists_them(engine):
    out = QueryService(engine).suppressions()
    assert out["defaultsEnabled"] is True
    assert out["userRules"] == 2
    assert out["countsByOrigin"][ORIGIN_DEFAULT] >= 9
    assert out["count"] == len(out["rules"])
    assert "never silently omitted" in out["note"]
    assert "--no-default-suppressions" in out["note"]


def test_http_lists_them(server):
    with urllib.request.urlopen(server + "/v1/suppressions", timeout=10) as resp:
        out = json.loads(resp.read())
    assert out["defaultsEnabled"] is True
    assert any(row["pattern"] == "*#hashCode()I" for row in out["rules"])
    assert any(row["origin"] == ORIGIN_USER for row in out["rules"])


def test_mcp_lists_them(engine):
    from ax_server.mcp.server import McpServer

    mcp = McpServer(QueryService(engine))
    resp = mcp.handle(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {"name": "gt_suppressions", "arguments": {}},
        }
    )
    out = json.loads(resp["result"]["content"][0]["text"])
    assert out["countsByOrigin"][ORIGIN_DEFAULT] >= 9
    assert "--no-default-suppressions" in mcp.tools["gt_suppressions"].description


# ======================================================================
# 4. every match is ATTRIBUTED, and still UNKNOWN rather than omitted
# ======================================================================


LOMBOK_MANIFEST = {
    "schemaVersion": 2,
    "buildSha": BUILD_SHA,
    "artifact": "checkout-service",
    "classes": [
        {
            "name": DATA,
            "sourceFile": "OrderDto.java",
            "probeCount": 3,
            "schemaHash": "d00d",
            "methods": [
                {"idx": 0, "name": "hashCode", "desc": "()I",
                 "dynamicallyObservable": True, "shortCircuitable": False},
                {"idx": 1, "name": "toString", "desc": "()Ljava/lang/String;",
                 "dynamicallyObservable": True, "shortCircuitable": False},
                {"idx": 2, "name": "describe", "desc": "()Ljava/lang/String;",
                 "dynamicallyObservable": True, "shortCircuitable": False},
            ],
        }
    ],
    "entryPoints": [],
    # All three methods appear as graph NODES with no inbound resolved edge, so
    # `Reachability.classify` returns False (the analyser demonstrably looked)
    # rather than None. Without that the `static-unresolved` blocker fires and
    # every method is UNKNOWN for a reason that has nothing to do with #28.
    "callEdges": [
        {"from": "com.acme.order.OrderDto#hashCode()I",
         "to": "com.acme.order.Fmt#pad()V", "resolution": "exact"},
        {"from": "com.acme.order.OrderDto#toString()Ljava/lang/String;",
         "to": "com.acme.order.Fmt#pad()V", "resolution": "exact"},
        {"from": "com.acme.order.OrderDto#describe()Ljava/lang/String;",
         "to": "com.acme.order.Fmt#pad()V", "resolution": "exact"},
    ],
}


def _lombok_engine(store, calendar, *, defaults=True, user=""):
    return AnalysisEngine(
        store,
        load_manifest(LOMBOK_MANIFEST),
        config=AnalysisConfig(phase_calendar=calendar, default_suppressions=defaults),
        suppressions=Suppressions.from_text(user, source=".auxin/suppress.txt"),
        ledger=SqliteProposalLedger(":memory:"),
    )


def _lombok_windows(collector):
    """Four production windows in which none of the three methods ran."""
    from conftest import installed_mask, probes

    for day in range(4):
        body = realistic_payload(day)
        body["classesLoaded"] = [DATA]
        body["coverage"] = [
            {
                "class": DATA,
                "schemaHash": "d00d",
                "probes": probes(),
                "probesInstalled": installed_mask(0, 1, 2),
            }
        ]
        body["tier2"] = []
        assert collector.ingest(body).accepted


def test_a_default_suppression_yields_unknown_and_says_which_list(collector, store, calendar):
    _lombok_windows(collector)
    engine = _lombok_engine(store, calendar)
    try:
        by_key = {f"{v.method}{v.desc}": v for v in engine.derive(BUILD_SHA).verdicts}

        hashcode = by_key["hashCode()I"]
        assert hashcode.status == UNKNOWN, "suppression is UNKNOWN, never DEAD_CANDIDATE"
        assert hashcode.suppressed is True
        reason = next(r for r in hashcode.reasons if r.startswith("suppressed:"))
        assert "[default list]" in reason
        assert DEFAULT_SOURCE in reason
        assert "*#hashCode()I" in reason

        # ...and the method is STILL IN THE OUTPUT. Suppression is a verdict,
        # not a filter: a method missing from the list entirely is
        # indistinguishable from a method the analysis never saw.
        assert "toString()Ljava/lang/String;" in by_key
        assert "describe()Ljava/lang/String;" in by_key

        # The hand-written sibling, on identical evidence, is still nominated.
        assert by_key["describe()Ljava/lang/String;"].status == DEAD_CANDIDATE
    finally:
        engine.close()


def test_a_user_rule_wins_the_attribution(collector, store, calendar):
    """First match wins and user rules come first, so the user's own pattern
    and comment are what appear in `reasons` -- not ours."""
    _lombok_windows(collector)
    engine = _lombok_engine(
        store, calendar, user="com.acme.order.OrderDto#hashCode  # ours, deliberately\n"
    )
    try:
        verdict = next(
            v for v in engine.derive(BUILD_SHA).verdicts if v.method == "hashCode"
        )
        reason = next(r for r in verdict.reasons if r.startswith("suppressed:"))
        assert "[user list]" in reason
        assert ".auxin/suppress.txt" in reason
        assert "ours, deliberately" in reason
    finally:
        engine.close()


def test_no_default_suppressions_turns_the_set_off(collector, store, calendar):
    _lombok_windows(collector)
    engine = _lombok_engine(store, calendar, defaults=False)
    try:
        by_key = {f"{v.method}{v.desc}": v for v in engine.derive(BUILD_SHA).verdicts}
        assert by_key["hashCode()I"].status == DEAD_CANDIDATE
        assert by_key["hashCode()I"].suppressed is False
        assert len(engine.suppressions) == 0
        assert QueryService(engine).suppressions()["defaultsEnabled"] is False
    finally:
        engine.close()


def test_the_precision_win_is_real(collector, store, calendar):
    """The trial's headline number, reproduced in miniature: two of three
    candidates were compiler output, and the defaults remove exactly those."""
    _lombok_windows(collector)
    off = _lombok_engine(store, calendar, defaults=False)
    on = _lombok_engine(store, calendar, defaults=True)
    try:
        assert len(off.derive(BUILD_SHA).by_status(DEAD_CANDIDATE)) == 3
        remaining = on.derive(BUILD_SHA).by_status(DEAD_CANDIDATE)
        assert [v.method for v in remaining] == ["describe"]
    finally:
        off.close()
        on.close()


def test_the_engine_composes_the_defaults_in_one_place(store, manifest, calendar):
    """The CLI, the MCP server and the tests must all see the same rule set."""
    engine = AnalysisEngine(
        store,
        manifest,
        config=AnalysisConfig(phase_calendar=calendar),
        suppressions=Suppressions.from_text("com.acme.A#*\n"),
        ledger=SqliteProposalLedger(":memory:"),
    )
    try:
        assert len(engine.user_suppressions) == 1
        assert len(engine.suppressions) > 1
        assert engine.suppressions.counts_by_origin()[ORIGIN_USER] == 1
    finally:
        engine.close()


def test_the_fixture_suppression_file_still_parses_as_two_user_rules():
    """Guard: `from_file` stays PURE. Layering happens in `compose_suppressions`,
    so a caller that wants only the user's file still gets only that."""
    only_user = Suppressions.from_file(FIXTURES / "suppress.txt")
    assert len(only_user) == 2
    assert {r.origin for r in only_user} == {ORIGIN_USER}


def test_manifest_rules_are_listed_with_their_own_source(generated_manifest):
    composed = compose_suppressions(
        Suppressions(()), manifest=generated_manifest, include_defaults=True
    )
    sources = {row["source"] for row in composed.listing()}
    assert MANIFEST_SOURCE in sources
    assert DEFAULT_SOURCE in sources


@pytest.fixture
def server(store, collector, engine):
    import threading

    from ax_server.api.http import make_api_router
    from ax_server.collector.http import Router, make_collector_router, serve

    router = (
        Router()
        .extend(make_collector_router(collector))
        .extend(make_api_router(QueryService(engine, collector=collector)))
    )
    httpd = serve(router, port=0)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{httpd.server_address[1]}"
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)
