#!/bin/bash

# Specula Framework Setup Script (uv version, macOS Apple Silicon optimized)
# This script sets up the complete Specula environment with all dependencies

set -e  # Exit on any error

# Parse arguments
SKIP_MODEL=false
for arg in "$@"; do
    case $arg in
        --help|-h)
            echo "Specula Framework Setup Script"
            echo ""
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  --help, -h      Show this help message"
            echo "  --skip-model    Skip HuggingFace model download (faster setup)"
            echo ""
            exit 0
            ;;
        --skip-model)
            SKIP_MODEL=true
            ;;
    esac
done

echo "Setting up Specula Framework..."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper functions
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Get the script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

print_status "Project root: $PROJECT_ROOT"

# =============================================================================
# macOS Apple Silicon: Ensure Homebrew paths are available
# =============================================================================
if [[ "$OSTYPE" == "darwin"* ]]; then
    export PATH="/opt/homebrew/bin:/opt/homebrew/opt/openjdk/bin:$PATH"
fi

# =============================================================================
# Check/Install uv (modern Python package manager)
# =============================================================================
print_status "Checking for uv package manager..."

if command_exists uv; then
    print_success "uv found: $(uv --version)"
else
    print_status "Installing uv..."
    if [[ "$OSTYPE" == "darwin"* ]] && command_exists brew; then
        brew install uv
    elif command_exists curl; then
        curl -LsSf https://astral.sh/uv/install.sh | sh
        export PATH="$HOME/.local/bin:$PATH"
    else
        print_error "Cannot install uv. Please install manually: https://docs.astral.sh/uv/getting-started/installation/"
        exit 1
    fi
    
    if command_exists uv; then
        print_success "uv installed successfully"
    else
        print_error "uv installation failed"
        exit 1
    fi
fi

# =============================================================================
# Check Java (required for TLA+ tools)
# =============================================================================
print_status "Checking system requirements..."

if command_exists java; then
    JAVA_VERSION=$(java -version 2>&1 | head -n1)
    print_success "Java found: $JAVA_VERSION"
else
    print_error "Java 11+ is required but not found"
    print_status "Please install Java 11+:"
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        print_status "  Ubuntu/Debian: sudo apt install openjdk-11-jdk"
        print_status "  CentOS/RHEL: sudo yum install java-11-openjdk-devel"
    else
        print_status "  macOS: brew install openjdk"
        print_status "  sudo ln -sfn /opt/homebrew/opt/openjdk/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk.jdk"
    fi
    exit 1
fi

# =============================================================================
# Check optional dependencies (warn but don't fail)
# =============================================================================

# Check Maven (optional, for CFA tool in step2)
if command_exists mvn; then
    MVN_VERSION=$(mvn -version 2>&1 | head -n1 | cut -d' ' -f3)
    print_success "Maven found: $MVN_VERSION"
else
    print_warning "Maven not found - required for step2 (CFA tool)"
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        print_status "  Ubuntu/Debian: sudo apt install maven"
        print_status "  CentOS/RHEL: sudo yum install maven"
    else
        print_status "  macOS: brew install maven"
    fi
fi

# Check Go (optional, for etcd example)
if command_exists go; then
    GO_VERSION=$(go version | cut -d' ' -f3)
    print_success "Go found: $GO_VERSION"
else
    print_warning "Go not found - required for etcd example instrumentation"
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        print_status "  Ubuntu/Debian: sudo apt install golang-go"
    else
        print_status "  macOS: brew install go"
    fi
fi

# =============================================================================
# Create Python virtual environment with uv
# =============================================================================
print_status "Setting up Python virtual environment..."
cd "$PROJECT_ROOT"

if [ -d ".venv" ]; then
    print_warning "Existing .venv found, removing..."
    rm -rf .venv
fi

# Create venv - try Python versions in order of preference
print_status "Creating virtual environment..."
for PYTHON_VERSION in 3.12 3.11 3.10; do
    if uv venv --python "$PYTHON_VERSION" 2>/dev/null; then
        print_success "Virtual environment created with Python $PYTHON_VERSION"
        break
    fi
done

if [ ! -d ".venv" ]; then
    print_error "Failed to create virtual environment. Please install Python 3.10+"
    exit 1
fi

# =============================================================================
# Install Python dependencies
# =============================================================================
print_status "Installing Python dependencies..."

# Handle PyTorch for different platforms
TEMP_REQUIREMENTS=$(mktemp)
if [ -f "src/requirements.txt" ]; then
    if [[ "$OSTYPE" == "darwin"* ]]; then
        # Apple Silicon: skip CPU-only index, use MPS-enabled default
        grep -v "extra-index-url.*pytorch.*cpu" "src/requirements.txt" > "$TEMP_REQUIREMENTS"
        print_status "Installing from src/requirements.txt (Apple Silicon optimized)..."
    else
        # Linux: use CPU-only PyTorch as specified in requirements
        cp "src/requirements.txt" "$TEMP_REQUIREMENTS"
        print_status "Installing from src/requirements.txt..."
    fi
    uv pip install -r "$TEMP_REQUIREMENTS"
    rm "$TEMP_REQUIREMENTS"
    print_success "Python dependencies installed"
else
    print_error "src/requirements.txt not found"
    exit 1
fi

# =============================================================================
# Create necessary directories
# =============================================================================
print_status "Creating necessary directories..."
mkdir -p "$PROJECT_ROOT/lib"
mkdir -p "$PROJECT_ROOT/models"
mkdir -p "$PROJECT_ROOT/output"

# =============================================================================
# Set up TLA+ tools
# =============================================================================
print_status "Setting up TLA+ tools..."

# Check if TLA+ Toolbox is installed and symlink its jar
TLA_TOOLBOX_JAR="/Applications/TLA+ Toolbox.app/Contents/Eclipse/tla2tools.jar"

if [ -f "$TLA_TOOLBOX_JAR" ]; then
    print_status "Found TLA+ Toolbox installation, creating symlink..."
    ln -sf "$TLA_TOOLBOX_JAR" "$PROJECT_ROOT/lib/tla2tools.jar"
    print_success "Symlinked tla2tools.jar from TLA+ Toolbox"
elif [ ! -f "$PROJECT_ROOT/lib/tla2tools.jar" ]; then
    print_status "Downloading tla2tools.jar..."
    TLA_TOOLS_URL="https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar"
    curl -L -o "$PROJECT_ROOT/lib/tla2tools.jar" "$TLA_TOOLS_URL"
    print_success "tla2tools.jar downloaded"
else
    print_success "tla2tools.jar already exists"
fi

# Download CommunityModules-deps.jar if not exists
if [ ! -f "$PROJECT_ROOT/lib/CommunityModules-deps.jar" ]; then
    print_status "Downloading CommunityModules-deps.jar..."
    COMMUNITY_MODULES_URL="https://github.com/tlaplus/CommunityModules/releases/download/202505152026/CommunityModules-deps.jar"
    curl -L -o "$PROJECT_ROOT/lib/CommunityModules-deps.jar" "$COMMUNITY_MODULES_URL" || {
        print_warning "CommunityModules-deps.jar download failed - optional for basic functionality"
    }
    [ -f "$PROJECT_ROOT/lib/CommunityModules-deps.jar" ] && print_success "CommunityModules-deps.jar downloaded"
else
    print_success "CommunityModules-deps.jar already exists"
fi

# =============================================================================
# Download Hugging Face model (for RAG functionality)
# =============================================================================
if [ "$SKIP_MODEL" = false ]; then
    print_status "Setting up Hugging Face embedding model..."
    MODEL_DIR="$PROJECT_ROOT/models/huggingface-MiniLM-L6-v2"

    if [ ! -d "$MODEL_DIR" ] || [ -z "$(ls -A "$MODEL_DIR" 2>/dev/null)" ]; then
        print_status "Downloading sentence-transformers/all-MiniLM-L6-v2 model..."
        mkdir -p "$MODEL_DIR"

        # Use the venv Python
        "$PROJECT_ROOT/.venv/bin/python" -c "
from sentence_transformers import SentenceTransformer

model_name = 'sentence-transformers/all-MiniLM-L6-v2'
target_dir = '$MODEL_DIR'

print(f'Downloading model: {model_name}')
model = SentenceTransformer(model_name)
model.save(target_dir)
print(f'Model saved to: {target_dir}')
" && print_success "Hugging Face model downloaded" || {
            print_warning "Model download failed - will be downloaded on first use"
        }
    else
        print_success "Hugging Face model already exists"
    fi
else
    print_status "Skipping HuggingFace model download (--skip-model flag)"
fi

# =============================================================================
# Set up example directories
# =============================================================================
print_status "Setting up example directories..."
mkdir -p "$PROJECT_ROOT/examples/etcd/"{config,source,output,runners,spec,scripts}
mkdir -p "$PROJECT_ROOT/examples/etcd/spec/step4/spec"

# Copy default config if not exists
if [ ! -f "$PROJECT_ROOT/examples/etcd/config/raft_config.yaml" ]; then
    print_status "Creating default raft_config.yaml..."
    cat > "$PROJECT_ROOT/examples/etcd/config/raft_config.yaml" << 'EOF'
# Specula Configuration for etcd/raft
system_name: "etcd-raft"
language: "go"

# TLA+ action mappings
actions:
  - name: "tickElection"
    functions: ["tickElection"]
    description: "Handle election timeout"
  
  - name: "tickHeartbeat" 
    functions: ["tickHeartbeat"]
    description: "Handle heartbeat timeout"
  
  - name: "Step"
    functions: ["Step"]
    description: "Process incoming message"

# Instrumentation settings
instrumentation:
  template_language: "go"
  trace_output_format: "ndjson"

# TLA+ specification settings
spec:
  constants:
    Server: ["n1", "n2", "n3"]
    Value: [1, 2, 3]
    Nil: ["Nil"]
    NoLimit: ["NoLimit"]
EOF
    print_success "Default raft_config.yaml created"
fi

# =============================================================================
# Verify installation
# =============================================================================
print_status "Verifying installation..."

# Test Java with TLA+ tools
if java -cp "$PROJECT_ROOT/lib/tla2tools.jar" tlc2.TLC -help >/dev/null 2>&1; then
    print_success "TLA+ tools working correctly"
else
    print_warning "TLA+ tools verification failed - may need manual setup"
fi

# Test Python imports using venv
print_status "Testing Python dependencies..."
"$PROJECT_ROOT/.venv/bin/python" -c "
import sys
packages = ['yaml', 'anthropic', 'openai', 'requests', 'torch', 'sentence_transformers', 'numpy', 'mcp']

for package in packages:
    try:
        __import__(package if package != 'yaml' else 'yaml')
        print(f'✓ {package}')
    except ImportError as e:
        print(f'✗ {package}: {e}')
        sys.exit(1)

print('All Python dependencies are available')
" && print_success "Python environment OK" || print_warning "Some Python dependencies missing"

# =============================================================================
# Create specula command wrapper (uses venv automatically)
# =============================================================================
print_status "Creating specula command wrapper..."

cat > "$PROJECT_ROOT/specula" << 'EOF'
#!/bin/bash
# Specula Framework Command Wrapper
# Uses the project's virtual environment automatically

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_PYTHON="$SCRIPT_DIR/.venv/bin/python"

# Check venv exists
if [ ! -f "$VENV_PYTHON" ]; then
    echo "Error: Virtual environment not found at $SCRIPT_DIR/.venv"
    echo "Please run: bash scripts/setup.sh"
    exit 1
fi

export PYTHONPATH="$SCRIPT_DIR/src:$PYTHONPATH"

# Check if no arguments provided
if [ $# -eq 0 ]; then
    echo "Specula Framework - Unified Command Interface"
    echo
    echo "Usage: $0 <command> [arguments...]"
    echo
    echo "Commands:"
    echo "  step1 [args...]    Generate TLA+ specification from source code"
    echo "  step2 [args...]    Transform TLA+ spec using CFA tool"
    echo "  step3 [args...]    Verify TLA+ specification using TLC"
    echo "  step4 [args...]    Run combined trace validation pipeline"
    echo "  step4.1 [args...]  Generate trace validation configuration"
    echo "  step4.2 [args...]  Instrument source code for tracing"
    echo
    echo "Examples:"
    echo "  $0 step1 examples/etcd/source/raft.go output/etcd/spec/step1/ --mode draft-based"
    echo "  $0 step2 output/etcd/spec/step1/corrected_spec/Raft.tla output/etcd/spec/step2/Raft.tla"
    echo "  $0 step3 output/etcd/spec/step2/Raft.tla --model-check"
    exit 0
fi

COMMAND="$1"
shift

case "$COMMAND" in
    "step1")
        "$VENV_PYTHON" -m src.core.iispec_generator "$@"
        ;;
        
    "step2")
        if [ $# -lt 2 ]; then
            echo "Error: step2 requires input and output arguments"
            echo "Usage: $0 step2 <input> <output> [--algorithm <algorithm>] [--show-tree] [--debug]"
            exit 1
        fi
        
        INPUT_FILE="$1"
        OUTPUT_FILE="$2"
        shift 2
        
        CFA_SCRIPT="$SCRIPT_DIR/tools/cfa/run.sh"
        if [ ! -f "$CFA_SCRIPT" ]; then
            echo "Error: CFA tool not found at: $CFA_SCRIPT"
            exit 1
        fi
        
        mkdir -p "$(dirname "$OUTPUT_FILE")"
        bash "$CFA_SCRIPT" "$INPUT_FILE" "$OUTPUT_FILE" "$@"
        ;;
        
    "step3")
        "$VENV_PYTHON" -m src.core.runtime_corrector "$@"
        ;;

    "step4")
        "$VENV_PYTHON" -m src.core.combined_step4 "$@"
        ;;
        
    "step4.1")
        "$VENV_PYTHON" -m src.core.spectrace_generator "$@"
        ;;
        
    "step4.2")
        "$VENV_PYTHON" -m src.core.instrumentation "$@"
        ;;
        
    *)
        echo "Error: Unknown command: $COMMAND"
        echo "Use '$0' with no arguments to see usage information"
        exit 1
        ;;
esac
EOF
chmod +x "$PROJECT_ROOT/specula"

# =============================================================================
# Done!
# =============================================================================
print_success "Setup completed successfully!"
echo
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}Specula is ready to use!${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo
print_status "Quick start:"
echo "  export ANTHROPIC_API_KEY=your_key_here"
echo "  ./specula step1 examples/etcd/source/raft.go output/etcd/spec/step1/ --mode draft-based"
echo
print_status "Note: The ./specula command uses the virtual environment automatically."
print_status "No need to activate .venv manually!"

