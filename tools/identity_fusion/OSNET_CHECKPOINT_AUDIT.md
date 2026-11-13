# OSNet checkpoint-stage and source-byte audit — 20 October 2026

**Research only.** The code in `audit_osnet_checkpoint.py` reads neither
photographs nor embeddings. It never executes any Torch/Pickle checkpoint,
imports ML packages, downloads remote bytes, infers a person's identity, or
writes to the local People database. It is an *explicit no-inference*
checkpoint-stage and byte-integrity check, not checkpoint approval.

## Concrete trap: backbone-pretraining vs ReID training

The official [Torchreid model zoo](https://github.com/KaiyangZhou/deep-person-reid/blob/master/docs/MODEL_ZOO.md)
separates **ImageNet-pretrained backbones** from **same-domain** and
**cross-domain** person-ReID-trained weights. Those are not interchangeable.
The [OSNet Hugging Face publisher account](https://huggingface.co/kaiyangzhou/osnet/tree/01af85e82a9db4f3a4f6ed3a72ed9150bd416d04)
also provides both kinds. The repository card displays an **MIT license
label**, which is useful metadata but does **not** independently verify
licensing or consent for every training image, exact weight redistribution
rights, nor suitability for persistent identity association.

The checkpoint audit has a fixed, closed publisher register, corroborated
on **20 October 2026** at revision
`01af85e82a9db4f3a4f6ed3a72ed9150bd416d04`:

| Artifact key | Publisher-labeled stage | Published SHA-256 | Independent legal status |
| --- | --- | --- | --- |
| `osnet_x0_25_imagenet` | ImageNet backbone only | `f54941a66bad4ddd07f2907f498c810ce639ce7a1abeaf2a151f8da118d84693` | Dataset/weight rights not independently approved |
| `osnet_x0_25_msmt17` | MSMT17 person-ReID training | `cf55163d78fc44c62c82f85ab62d39f10438679b5abe8c698ae08cfa84aa6e18` | Training-data/consent and weight rights unresolved |
| `osnet_x0_5_imagenet` | ImageNet backbone only | `f10ea9e26ee55b1a65cee3c6e88aa1ae105fe981af3e37692fa68a72fc64bf8e` | Dataset/weight rights not independently approved |
| `osnet_x0_5_msmt17` | MSMT17 person-ReID training | `e96cbd20ee9cc3c6dcc0e8f4fbba8c8069d47647a5a96a59ce381fb785c54f68` | Training-data/consent and weight rights unresolved |

The SHA-256 values are **publisher-displayed** file fingerprints, not
independently signed attestations. The source revision is immutable-looking
Git content but this tool does not cryptographically authenticate the
publisher, repository ownership or training process. The model stage is a
publisher filename/model-zoo *claim*, not inspected checkpoint semantics.

Fixed source file descriptions and SHA-256 metadata (no weight downloads):
- [x0.25 ImageNet](https://huggingface.co/kaiyangzhou/osnet/blob/01af85e82a9db4f3a4f6ed3a72ed9150bd416d04/osnet_x0_25_imagenet.pth)
- [x0.25 MSMT17-trained](https://huggingface.co/kaiyangzhou/osnet/blob/01af85e82a9db4f3a4f6ed3a72ed9150bd416d04/osnet_x0_25_msmt17_combineall_256x128_amsgrad_ep150_stp60_lr0.0015_b64_fb10_softmax_labelsmooth_flip_jitter.pth)
- [x0.5 ImageNet](https://huggingface.co/kaiyangzhou/osnet/blob/01af85e82a9db4f3a4f6ed3a72ed9150bd416d04/osnet_x0_5_imagenet.pth)
- [x0.5 MSMT17-trained](https://huggingface.co/kaiyangzhou/osnet/blob/01af85e82a9db4f3a4f6ed3a72ed9150bd416d04/osnet_x0_5_msmt17_combineall_256x128_amsgrad_ep150_stp60_lr0.0015_b64_fb10_softmax_labelsmooth_flip_jitter.pth)

## Legal caution, even when labels look permissive

Older pedestrian ReID datasets are not necessarily ethically or legally
cleared just because papers cited them. Notably the separate **DukeMTMC**
collection has been withdrawn/retracted amid surveillance/consent concerns;
see [the dataset catalog warning](https://paperswithcode.com/dataset/dukemtmc-reid)
and [the project shutdown history](https://exposing.ai/duke_mtmc/).
**Do not infer that MSMT17 has the same legal status as DukeMTMC**; it
needs its own rights/provenance review. The currently identified MSMT17-trained
checkpoint cannot inherit blanket permission merely from the MIT model-card
label. Likewise the Torchreid **Market-1501 rank-1 percentages belong to
specific trained checkpoints/protocols**, not to an arbitrary x0.25 ImageNet
backbone, the MSMT17 Hugging Face checkpoint, or a family archive.

## Safe offline usage

From the repository root, inspect **public metadata only** (no local file):

```sh
python tools/identity_fusion/audit_osnet_checkpoint.py --artifact osnet_x0_25_imagenet
python tools/identity_fusion/audit_osnet_checkpoint.py --artifact osnet_x0_25_msmt17
```

If a checkpoint is acquired **independently, legally and deliberately**,
optionally verify an existing single regular file, without deserializing it:

```sh
python tools/identity_fusion/audit_osnet_checkpoint.py \
  --artifact osnet_x0_25_msmt17 --file /local/approved-staging/checkpoint.pth
```

The tool streams SHA-256 with a **32 MiB maximum**, refuses symlinks
(including parent paths), hardlink aliases, empty/nonregular files, changed
file metadata and digest mismatch, and never echoes user paths or model data
in reports/errors. A success reports `local_staged_checkpoint_digest_verified=true`
**only** for that publisher-listed digest. It always reports false for
independent legal clearance, execution safety, S24+ performance, actual
reidentification accuracy, native packaging and automatic identity linking.
It does not load/download any weights in CI.

Test only invented text bytes and mocked filesystem hazards:

```sh
python tools/identity_fusion/test_audit_osnet_checkpoint.py
python -m unittest discover -s tools/identity_fusion -p 'test_*.py'
```

Before any authorized model inference: verify exact trained-stage checkpoint
semantics/rights with explicit training source evidence, avoid untrusted
`torch.load` pickle execution, safely export into a format with independently
validated graph + pixel/channel/resize contract, then benchmark fully local
held-out known/Unknown rear/partial pairs and S24+ p95/RSS/thermal budgets.
Never present clothing or a body-only similarity as a confirmed identity.
