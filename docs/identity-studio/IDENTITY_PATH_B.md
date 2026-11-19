# Plan B: a licence-clean identity path for a GHOST-class swapper (IDS-16, #507)

**Question.** [#505](https://github.com/LPStruiksma/atlas-gallery/issues/505) found that GHOST 1's identity backbone is InsightFace's research-only `ms1mv3_arcface_r100_fp16` ([GHOST_BACKBONE.md](GHOST_BACKBONE.md)). Every swap network the bench measured therefore depends on a research-only InsightFace model. What are the realistic ways to get an identity path ATLAS may ship, what does each cost, and how good would it be?

**Answer in one paragraph.** (a) A small adapter from the shipped SFace embedding into GHOST's space is cheap but does not work: on the full benchmark it loses 0.20 of judge similarity against GHOST's own backbone (0.491 against 0.691), far outside the noise band, and it is not licence-clean anyway, because fitting it needs the research-only backbone's outputs. (b) Re-training GHOST's generator against a permissive encoder on permissively licensed faces is the only route that ends in something ATLAS owns outright. It needs paid GPU time (estimated EUR 60 to 250, one to three weeks of elapsed work) and its quality is unknown until it is trained; a realistic expectation is between the adapter and today's GHOST. (c) A commercial licence from InsightFace (for InSwapper, or for the ArcFace model GHOST and HyperSwap depend on) would give the best quality at once, but the price is not public and only InsightFace can say yes. The decision table is at the end.

All prices and training times below are **estimates**, marked as such, not quotes.

## The measured baseline

The IDS-2 bench ([BENCH.md](BENCH.md)) ran GHOST 1 through FaceFusion's ONNX file, which takes the `arcface_w600k_r50` embedding through a small converter network (CrossFace). This unit adds the source that GHOST was trained with, its own backbone, and two adapters from SFace. Everything else is the IDS-2 pipeline unchanged: YuNet landmarks, the face-parser mask, colour match, paste-back, the 58 photoreal pairs, and glintr100 as the research judge.

| Identity source fed to GHOST 1 | Judge mean | Paired difference to own backbone (95 % bootstrap) | Over 0.40 | Rank-1 | Judge to original face | SFace to references |
|---|---|---|---|---|---|---|
| GHOST's own backbone (research-only, bench only) | **0.691** | | 1.00 | 1.00 | 0.188 | 0.599 |
| `w600k_r50` through CrossFace (the IDS-2 row) | 0.668 | -0.022 (-0.035 to -0.010) | 1.00 | 1.00 | 0.185 | 0.590 |
| SFace through a linear adapter | 0.491 | **-0.200 (-0.217 to -0.183)** | 0.79 | 0.81 | 0.252 | 0.539 |
| SFace through an MLP adapter | 0.388 | -0.303 (-0.328 to -0.279) | 0.48 | 0.79 | 0.214 | 0.468 |

Same 58 pairs for every row, all 232 swaps succeeded. The CrossFace row reproduces BENCH.md's GHOST row exactly (0.668), which checks that the probe runs the IDS-2 pipeline. For scale: the IDS-2 noise band from landmark jitter is a standard deviation of 0.015 for GHOST, and the paired gap from GHOST to InSwapper is 0.085.

Two things follow:

- **GHOST with its own backbone is a little better than the bench measured** (+0.022). The FaceFusion route under-reported it. [#506](https://github.com/LPStruiksma/atlas-gallery/issues/506) re-measures this with an export from the official release.
- **The SFace adapters are far below the line.** With the linear adapter, one swap in five is no longer recognised as the right person (rank-1 0.81) and one in five falls under the 0.40 same-person threshold. The MLP is worse: it fits its training faces almost perfectly and generalises worst.

### How the adapters were fitted

- **Faces.** The FLUXSynID pool ([FIXTURES.md](../../tools/identity_bench/FIXTURES.md)) minus every benchmark, look-alike and development identity: 209 identities, 836 photos, each also mirrored, 1,672 faces. The split is by identity: 1,336 faces for fitting, 336 from 42 unseen identities for validation. The adapters used for the swaps are refitted on all 1,672.
- **Targets.** For each face, the SFace embedding (input) and the raw 512-d output of GHOST's backbone (target), both on the same YuNet landmarks. Using the research-only backbone as a bench-time target is allowed by its register row; it is also exactly the licence problem discussed below.
- **Adapters.** A ridge linear map from the unit SFace vector to GHOST's raw vector, and a two-layer MLP (1,024 hidden units, cosine loss, Adam, 300 epochs, weight decay). Both rescale their output to GHOST's mean norm (about 18), because the generator was trained on raw, un-normalised vectors.

| Adapter | Cosine to GHOST's vector, fitting faces | Cosine, unseen identities |
|---|---|---|
| Linear (ridge) | 0.746 | 0.539 |
| MLP | 0.992 | 0.430 |

For comparison, FaceFusion's CrossFace, a converter from a 512-d InsightFace embedding trained on far more data, reaches 0.888 against GHOST's own vector (#505).

How much would more faces help? The linear adapter refitted on growing shares of the training identities, ridge chosen on the validation identities (`learning_curve` in the probe):

| Training identities (faces) | 17 (136) | 42 (336) | 84 (672) | 125 (1,000) | 167 (1,336) |
|---|---|---|---|---|---|
| Cosine to GHOST's vector, unseen identities | 0.341 | 0.421 | 0.487 | 0.538 | 0.563 |

The swaps above used the probe's default ridge (0.539 on unseen identities); the best ridge here reaches 0.563 with the same faces, too small a change to move the conclusion. For reference, one person's GHOST vector has a cosine of 0.844 to the mean of their other photos: that is how close a perfect adapter would have to land on a single photo.

### What would make (a) better, and why it is not worth it

The curve has not flattened: each doubling of training identities adds about 0.07 of cosine. Extrapolating that is unsafe, but if it held, matching CrossFace (0.888) would need about 25 times as many identities, some 4,000. Whether SFace can carry that much is open: it is a 128-d int8 model built to say "same person or not" on a phone, and GHOST's generator was trained to paint whatever its 512-d r100 vector encodes. CrossFace closes the gap from a 512-d model of the same family, and nothing permissive of that strength exists to start from. A bigger fit would also need thousands of permissively licensed identities, and it would still not be licence-clean (next section). So this route does not deserve a larger run.

## The licence question for (a): does an adapter inherit the backbone's terms?

**Our answer: treat it as not clean.** This is our reading, not legal advice.

1. **The fitting itself uses the research-only model.** InsightFace's terms say its models are "available for non-commercial research purposes only" ([README, License](https://github.com/deepinsight/insightface/blob/master/README.md#license)). Producing the targets means running that model. Running it to build a component that ATLAS then ships is a use for a product, not research, whether or not the result counts as a derivative. The terms bind the act, so the question of what the adapter "is" does not need an answer.
2. **The adapter's value is the backbone's embedding space.** Its only job is to reproduce the outputs of the research-only model. The licence family most model publishers use for this situation, OpenRAIL-M, defines "Derivatives of the Model" to include "any other model which is created or initialized by transfer of patterns of the weights, parameters, activations or output of the Model", naming distillation explicitly ([BigScience OpenRAIL-M, section 1(e)](https://licenses.ai/s/BigScience-Open-RAIL-M-License.pdf)). InsightFace's own terms say nothing that narrow, but a court or a rights holder reading "non-commercial research purposes only" is unlikely to read it more generously than the industry's standard text.
3. **Whether model weights are copyright works at all is unsettled**, in the EU as elsewhere. That cuts both ways, so it cannot be relied on. The contractual terms in point 1 apply regardless.
4. **The training faces are non-commercial too.** FLUXSynID is CC BY-NC 4.0 and made with FLUX.1 [dev] (non-commercial). A shippable adapter would also need permissively licensed faces. ATLAS is a personal, non-commercial app today ([FIXTURES.md](../../tools/identity_bench/FIXTURES.md)), but "non-commercial" is not "research", and InsightFace's grant is for research.

**The same reasoning reaches GHOST's generator, more weakly.** ai-forever trained the generator against the backbone's embeddings (identity loss and identity conditioning), so its weights also carry patterns of that model's output. Here, though, the user of the research-only model was ai-forever, who then released the generator under Apache-2.0. That is a provenance gap (register tier D, as LICENCES.md already says), not a direct use by ATLAS. Any route that keeps today's generator weights keeps this gap; only route (b) removes it.

## Option (b): re-train the GHOST generator on a permissive encoder

**What is needed.**

- **Encoder.** SFace (Apache-2.0, already shipped) is the only permissively licensed recognition model in the register. Its weights are cleared; its training data is not stated in the opencv_zoo README. The register already accepts that for the shipped identity check; a re-trained generator would lean on it more.
- **Generator change.** GHOST's AAD layers read the identity through small fully connected layers (`fc1`, `fc2` in each AAD layer, `c_id` inputs). Moving from 512-d to SFace's 128-d means re-initialising those layers. A start from the existing weights would keep a provenance gap (above); a clean result means training from scratch with GHOST's Apache-2.0 code.
- **Faces to train on.** AEI-Net training (GHOST's method) needs many faces, not many photos per person: pairs are drawn at random and the identity loss comes from the encoder. Candidates:
  - VGGFace2 (what GHOST used, about 3.3 million images): scraped photos of real people, withdrawn by its authors; not usable for a product.
  - FFHQ: CC BY-NC-SA for the dataset; not usable.
  - SFHQ (about 425k synthetic faces, MIT label): generated with StyleGAN2, whose NVIDIA licence is for "research or evaluation purposes only"; not clean.
  - **Generate our own** with FLUX.1 [schnell] (Apache-2.0 per its [model card](https://huggingface.co/black-forest-labs/FLUX.1-schnell); not re-read in this session): about 100k varied portraits are enough for a 256 px generator. No identity conditioning is needed, which avoids Arc2Face/PuLID (both built on InsightFace embeddings). The register must read the schnell card and record what is known about its training data before this starts.
- **Compute (estimate).** One RTX 4090-class GPU at about USD 0.35 to 0.50 an hour on community clouds (third-party price snapshots: [beam.cloud](https://www.beam.cloud/blog/nvidia-rtx-4090-pricing), [getdeploying](https://getdeploying.com/reference/cloud-gpu/nvidia-rtx4090); live prices could not be checked from here).
  - Generating 100k faces: about 30 to 50 GPU hours, EUR 10 to 25.
  - One from-scratch training run of a 1-block, 256 px AEI-Net: 3 to 5 days, EUR 25 to 60.
  - Two or three runs to get settings right, plus evaluation: **EUR 60 to 250 in total**, one to three weeks elapsed.
- **Quality expectation.** Unknown until trained. The identity it can carry is bounded by what SFace encodes, which is what limited the adapter. A realistic range is a judge mean of **0.50 to 0.65** against today's 0.69. One risk is specific: a generator trained to satisfy SFace can learn features that fool SFace without looking more like the person to other recognisers. The bench's independent judge (glintr100) exists to catch exactly that.

Paid GPU time would need its own owner card with a price before anything starts (#507 "Not this"). Under OD-1 this route is a documented fallback only.

## Option (c): a licence route

What InsightFace publicly says (README, [License](https://github.com/deepinsight/insightface/blob/master/README.md#license), read 2026-11-18):

- The models and the data they were trained on are "available for non-commercial research purposes only".
- InSwapper: commercial licensing through `contact@insightface.ai`.
- The open recognition models (the buffalo family, which includes the ArcFace embedding InSwapper and HyperSwap take as input): `recognition-oss-pack@insightface.ai`.
- Their site also lists an InSwapper-128 commercial licence and asks for enquiries from a company email address (search snippet only; insightface.ai is not reachable from this environment).

No price is published. Nobody has been contacted, as #507 instructs.

What a licence would cover:

| Licence | What ATLAS could ship | Quality (IDS-2 judge mean) | Notes |
|---|---|---|---|
| InSwapper-128 (InsightFace) | InSwapper with its `w600k_r50` encoder | 0.753 | Best measured; 128 px output, so boost is needed |
| ArcFace / r100 used by GHOST (InsightFace) | GHOST 1 with its own backbone (#506 export) | 0.691 | The generator's tier D gap remains, but the rights holder of the model it depends on has agreed |
| HyperSwap 1a (FaceFusion, ResearchRAIL label) | Needs FaceFusion's terms **and** an InsightFace licence for its `w600k_r50` input | 0.699 | Two parties; no public commercial route found for HyperSwap itself |

## Reproduce

```bash
python3 -m tools.identity_bench.planb.encoder_swap_probe            # about 50 min on 4 CPU threads
python3 -m unittest tools/identity_bench/planb/test_encoder_swap_probe.py
```

The probe downloads GHOST's backbone (bench-only) through `tools/identity_bench/provenance`, writes its result to `~/.cache/atlas-identity-bench/runs/planb/probe.json`, and keeps the fitted vectors (synthetic faces only) in the same folder. Nothing it produces is committed.

## For the owner decision

**Decided (2026-11-18):** Lukas chose private use, owner decision OD-1 on [#467](https://github.com/LPStruiksma/atlas-gallery/issues/467): research-licence models downloaded from their original hosts onto his own phone, never redistributed. So no owner card follows from this unit and nothing is spent. The retrain route stays documented as the fallback if ATLAS is ever distributed. The table is kept as the record of what was weighed.

| Route | What Lukas gets | Cost | Time | Quality expectation | Licence risk |
|---|---|---|---|---|---|
| **License from InsightFace** | GHOST 1 with its own backbone (0.691), or InSwapper (0.753) if that is the licence offered | Unknown; InsightFace sets it. One email from Lukas | Days to weeks, depending on their answer | Best available: as measured | Low for the licensed part; GHOST's generator keeps its tier D note |
| **Re-train GHOST on SFace** | A swapper ATLAS owns end to end: Apache-2.0 code, Apache-2.0 encoder, self-generated faces | Estimate EUR 60 to 250 GPU (separate card) plus about a week of Dev and Review time | One to three weeks | Unknown until trained; expect 0.50 to 0.65 against 0.69 today | Lowest: only the open questions on SFace's and FLUX.1 [schnell]'s training data remain |
| **Private use only, no licence** | GHOST 1 with its own backbone (0.691) on Lukas's own phone, never distributed | None | Ready after IDS-9 | As measured | High if ATLAS is ever distributed; even private use is outside "research" in InsightFace's wording |

The SFace adapter (route a) is not in the table: it loses 0.20 of identity and is not licence-clean.
