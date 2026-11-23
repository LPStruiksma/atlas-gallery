# NLS-1E tokenizer access and JVM parity investigation

Tracking: [#1006](https://github.com/LPStruiksma/atlas-gallery/issues/1006); staging integration: [#984](https://github.com/LPStruiksma/atlas-gallery/pull/984).

## Result (2026-11-23)

**Blocked: neither access/licence nor offline JVM parity is proven.** No model
bytes were obtained, no JVM tokenizer was selected, and no Python result is being
used as evidence of JVM parity.

## Exact object access

The object requested was exactly
`https://storage.googleapis.com/big_vision/gemma_tokenizer.model`. The expected
values remain 4,241,111 bytes and SHA-256
`6969e64047744a44bb3abfb5c50f8de0f7ed8b571d5444426ef931f651d1a0ef`, as
specified by #1006 and previously verified by a different seat. This seat did
not independently verify either value.

The non-authenticated, non-redirect-following request was:

    curl -sS -D /tmp/gemma_headers -o /tmp/gemma_body --max-time 30 https://storage.googleapis.com/big_vision/gemma_tokenizer.model

It exited 56 with `CONNECT tunnel failed, response 403`. The only response was
from the environment's Envoy CONNECT proxy: `HTTP/1.1 403 Forbidden`,
`content-length: 9`, `content-type: text/plain`; no output file was created.
This is an environment egress denial, not evidence of the Google object's HTTP
status or access policy. No alternate host, credential, mirror, or bypass was
used.

## Primary-source terms and access conclusion

Google's [Gemma Terms of Use](https://ai.google.dev/gemma/terms/) say that they
apply to the models listed in their Appendix. Section 1.1(c) defines “Gemma” as
those machine-learning language models, trained weights, and parameters;
Section 2.2 permits use only in accordance with the agreement; and Section 3.1
allows distribution subject to its downstream restrictions, agreement-copy,
modified-file notice, and `Notice`-file conditions. The page does not identify
the separately hosted `big_vision/gemma_tokenizer.model` object, its path, or
its digest. Google's [Gemma setup documentation](https://ai.google.dev/gemma/docs/setup)
describes gated model access through Kaggle or Hugging Face after acknowledging
the Gemma licence, but it does not document this public Cloud Storage object's
access conditions.

The first-party
[big_vision licence statement](https://github.com/google-research/big_vision/blob/0127fb6b337ee2a27bf4e54dea79cff176527356/README.md#license)
applies Apache-2.0 to repository contents and its released models, while its
[SigLIP 2 documentation](https://github.com/google-research/big_vision/blob/0127fb6b337ee2a27bf4e54dea79cff176527356/big_vision/configs/proj/image_text/README_siglip2.md)
points at a Gemma tokenizer. Neither primary source attaches a licence or access
terms to this exact independently hosted object/digest. Therefore local use and
redistribution of these exact bytes are not established by the available
primary sources. In particular, public downloadability (when available) must
not be treated as redistribution permission.

**Next owner action (Google/model owner):** provide a first-party statement
that names this exact URL and digest, states how a user may lawfully obtain and
use it locally, and states whether and under what notice/restriction conditions
it may be redistributed. The project owner must review and record acceptance of
those terms before any packaging decision.

## Offline JVM SentencePiece dependency audit

Two concrete Maven options were investigated, but neither meets the full proof
bar for this Android/offline/bounded-memory use:

* `ai.djl.sentencepiece:sentencepiece:0.40.0` is the current DJL Java wrapper
  published on Maven Central. DJL documents that it wraps the SentencePiece C++
  API and that native libraries are cached/downloaded separately. The DJL
  maintainers' [Android SentencePiece discussion](https://github.com/deepjavalibrary/djl/discussions/3742)
  records that the artifact has no Android native library and leaves the Android
  integration question unanswered. It is consequently not a self-contained,
  offline Android dependency for this task. Its Apache-2.0 wrapper licence does
  not cure the missing Android native artifact.
* `io.github.eix128:sentencepiece4j:1.0.1` is a pure-Java Java-8 artifact on
  Maven Central, declared Apache-2.0, with `protobuf-java` and `fastutil`
  dependencies. Those facts make it a legally usable candidate dependency in
  isolation, but its primary documentation supplies no model-size-independent
  or otherwise quantified heap bound, no Android compatibility/security
  support statement, and no parity evidence for the pinned Gemma model. Source
  checkout was also blocked in this seat by the same CONNECT 403, so those
  missing properties could not be independently audited here.

Google's official SentencePiece project is Apache-2.0 and its C++ processor can
be built for Android, but it does not publish an official JVM/Android artifact.
Its new `lite` runtime documents bounded heap in memory-mapped mode, but it is a
C++20/FlatBuffers runtime rather than a JVM dependency and requires conversion
from the pinned `.model`; it therefore cannot establish byte-for-byte parity on
the required input by itself.

**Dependency blocker:** no audited artifact currently satisfies all of JVM API,
offline Android packaging, a defensible memory bound, maintained/security
posture, and demonstrated compatibility with the exact model. “Pure Java” or a
successful desktop load alone is not a memory or Android proof.

**Next owner action (Android/runtime owner):** choose and security-review a
pinned candidate (or commission an Android binding), record all transitive
licences, make every native/runtime byte available offline, and define an
enforceable heap/input bound. Run it in an Android/JVM test process under that
bound before approving it.

## #217 EN/NL golden execution

The committed six-case EN/NL fixture passed its dependency-free structural
validator:

    python3 tools/visual_search_bench/nls1_tokenizer_golden.py --check
    verified 6 cases in tools/visual_search_bench/data/nls1_tokenizer_goldens.json

The mock-backed Python contract tests also passed, but they exercise no real
SentencePiece implementation:

    python3 tools/visual_search_bench/nls1_tokenizer_golden.py --self-test
    Ran 5 tests in 0.001s; OK

The requested #217 EN/NL **JVM** golden run was not executable: the exact model
could not be downloaded through the proxy, and there is no approved JVM
dependency or JVM golden runner in this parent. The fixture validation above
only checks lengths, sticky EOS, padding, normalization metadata, case set, and
the recorded digest; it does not re-tokenize text. Accordingly there is no JVM
parity claim.

**Next owner action (test owner, after both blockers close):** add an
evidence-only external JVM harness for the approved pinned dependency, load the
hash-verified model outside Git, tokenize every committed EN/NL input with no
automatic BOS/EOS, apply sticky EOS and right padding to length 64, and compare
all IDs to the fixture under the approved heap limit. Record Java/OS/CPU,
artifact and transitive versions, model byte count/digest, maximum heap, exact
command, and per-case result. A Python-only comparison is not acceptance.
