# FLUX.2 head admission scaffold

The `flux2-head` backend name reserves a review boundary; it is **not a
production-ready inference backend**. It performs no model import, download,
network request, or image generation. Selecting it without an exact opt-in
fails before the server starts.

## Admission contract

The default is disabled. For an offline protocol/admission exercise only, set:

```sh
ATLAS_BACKEND=flux2-head \
ATLAS_FLUX2_HEAD_ENABLED=true \
ATLAS_FLUX2_HEAD_MODE=scaffold \
python3 -m tools.ai_edit_server.server --host 127.0.0.1 --port 8080
```

`ATLAS_FLUX2_HEAD_ENABLED` accepts only the exact, case-sensitive value `true`.
The mode defaults to `scaffold` and no other value is admitted. In particular,
`full_head` is forbidden. An admitted scaffold reports its limited identity,
but every edit terminates as a backend error because inference is deliberately
unimplemented.

This gate is not evidence of model quality, safety, licence compliance,
hardware fit, production hardening, or deployment readiness. Enabling real
inference requires a separate reviewed change with an explicit model and
licence decision, resource bounds, cancellation behavior, input/output safety
controls, and offline tests. Do not expose this scaffold publicly or upload
personal media to it.

## Offline verification

The negative tests use synthetic bytes and patch only process environment:

```sh
python3 -m unittest discover -s tools/ai_edit_server/tests -p 'test_*.py' -v
```
