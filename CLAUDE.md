# Specula - Claude Context

## Project Overview

Specula is an automated framework for synthesizing TLA+ specifications from source code (Go, Rust). It enables formal verification of system designs through a 4-step pipeline.

## Quick Start

```bash
# Setup
bash scripts/setup.sh
export ANTHROPIC_API_KEY="your-key"

# Full pipeline (etcd Raft example)
./specula step1 examples/etcd/source/raft.go output/step1/ --mode draft-based
./specula step2 output/step1/corrected_spec/Raft.tla output/step2/Raft.tla
./specula step3 output/step2/Raft.tla output/step3/
```

## The 4-Step Pipeline

| Step | Purpose | Entry Point |
|------|---------|-------------|
| **Step 1** | Code → TLA+ translation | `src/core/iispec_generator.py` |
| **Step 2** | CFA transformation | `tools/cfa/` (Java) |
| **Step 3** | Runtime error correction | `src/core/runtime_corrector.py` |
| **Step 4** | Trace validation | `src/core/spectrace_generator.py` |

## Key Directories

```
src/
├── core/           # Step implementations
├── llm/client.py   # Multi-provider LLM client
├── rag/            # RAG for error correction
└── prompts/        # LLM prompts

tools/cfa/          # Java-based CFA tool (Step 2)
lib/tla2tools.jar   # TLA+ compiler (SANY) and model checker (TLC)
examples/etcd/      # Raft case study
```

## Configuration

**File:** `config.yaml`
```yaml
llm:
  provider: "anthropic"  # or openai, deepseek, gemini
  model: "claude-opus-4-5-20251101"
```

**Environment:** `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `DEEPSEEK_API_KEY`, `GEMINI_API_KEY`

## Known Issues

1. **Step 2 CFA parser** - Not fully robust; may need manual spec adjustments
2. **Path resolution** - Use absolute paths when running steps from different directories
3. **TLC config paths** - Config file must be in same directory as spec for Step 3

## Testing

```bash
pytest tests/                    # All tests
pytest tests/unit/tools/ -v      # Tool tests
```

## Debugging

```bash
./specula step1 ... --log-level DEBUG      # Verbose logging
java -cp lib/tla2tools.jar tla2sany.SANY spec.tla   # Validate TLA+ syntax
java -cp lib/tla2tools.jar tlc2.TLC -config spec.cfg spec.tla  # Run TLC directly
```

## Recent Fixes (muloka/enhancements branch)

- **Issue #1:** CFA tool path resolution - converts to absolute paths before SANY parsing
- **Issue #2:** TLC config file path - copies config to spec directory
- **Issue #3:** Migrated from deprecated `google-generativeai` to `google-genai` SDK
