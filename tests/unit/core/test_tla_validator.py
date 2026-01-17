"""
Unit tests for TLAValidator

Tests that TLAValidator correctly detects SANY errors, including semantic errors
that may occur even when SANY returns exit code 0.
"""

import os
import pytest
from pathlib import Path
from src.core.iispec_generator import TLAValidator


class TestTLAValidator:
    """Test cases for TLAValidator"""

    @pytest.fixture
    def validator(self):
        """Create a TLAValidator instance"""
        return TLAValidator()

    @pytest.fixture
    def valid_spec(self, tmp_path):
        """Create a valid TLA+ spec"""
        spec_content = """---- MODULE valid_spec ----
EXTENDS Naturals

VARIABLES x

Init == x = 0

Next == x' = x + 1

Spec == Init /\\ [][Next]_x

====
"""
        spec_file = tmp_path / "valid_spec.tla"
        spec_file.write_text(spec_content)
        return spec_file

    @pytest.fixture
    def syntax_error_spec(self, tmp_path):
        """Create a spec with syntax errors"""
        spec_content = """---- MODULE syntax_error_spec ----
EXTENDS Naturals

VARIABLES x

Init == x = 0

Next == x' = x +  \\* Missing operand

====
"""
        spec_file = tmp_path / "syntax_error_spec.tla"
        spec_file.write_text(spec_content)
        return spec_file

    @pytest.fixture
    def semantic_error_spec(self, tmp_path):
        """Create a spec with semantic errors (unknown operator)

        This is the key bug case: SANY returns exit code 0 but outputs
        "Unknown operator" errors. The validator must detect this.
        """
        spec_content = """---- MODULE semantic_error_spec ----
EXTENDS Naturals

VARIABLES x

\\* This references an operator that doesn't exist yet
Init == x = 0 /\\ UndefinedOperator

\\* Define it after use - causes "Unknown operator" error
UndefinedOperator == TRUE

====
"""
        spec_file = tmp_path / "semantic_error_spec.tla"
        spec_file.write_text(spec_content)
        return spec_file

    def test_valid_spec_passes(self, validator, valid_spec):
        """Test that a valid spec passes validation"""
        success, output = validator.validate(str(valid_spec))

        assert success is True
        assert "Semantic processing of module" in output

    def test_syntax_error_fails(self, validator, syntax_error_spec):
        """Test that a spec with syntax errors fails validation"""
        success, output = validator.validate(str(syntax_error_spec))

        assert success is False
        # Should contain error indicators
        assert any(indicator in output for indicator in ["Errors", "error", "Error"])

    def test_semantic_error_fails(self, validator, semantic_error_spec):
        """Test that a spec with semantic errors fails validation

        This is the critical test - SANY may return exit code 0 but still
        have semantic errors in the output. The validator must detect this.
        """
        success, output = validator.validate(str(semantic_error_spec))

        assert success is False
        # Should detect the semantic error
        assert "Unknown operator" in output or "Semantic errors" in output

    def test_file_not_found(self, validator):
        """Test that validation fails gracefully for nonexistent file"""
        success, output = validator.validate("/nonexistent/path/spec.tla")

        assert success is False
        # SANY says "Cannot find" or "does not exist" for missing files
        assert "cannot find" in output.lower() or "does not exist" in output.lower()

    def test_timeout_message(self, validator, valid_spec):
        """Test that timeout produces appropriate message"""
        # Save original timeout
        original_timeout = validator.timeout

        try:
            # Set impossibly short timeout to trigger timeout
            validator.timeout = 0.001
            success, output = validator.validate(str(valid_spec))

            # Either it times out or runs quickly enough to succeed
            # Both are acceptable outcomes for this test
            if not success:
                assert "timed out" in output.lower() or "timeout" in output.lower()
        finally:
            # Restore original timeout
            validator.timeout = original_timeout


class TestTLAValidatorErrorDetection:
    """Tests specifically for error detection logic"""

    @pytest.fixture
    def validator(self):
        """Create a TLAValidator instance"""
        return TLAValidator()

    def test_detects_fatal_errors(self, validator, tmp_path):
        """Test that 'Fatal errors' is detected"""
        # Create a malformed file that will cause fatal errors
        spec_file = tmp_path / "fatal.tla"
        spec_file.write_text("This is not valid TLA+ at all")

        success, output = validator.validate(str(spec_file))
        assert success is False

    def test_detects_semantic_errors_marker(self, validator, tmp_path):
        """Test that 'Semantic errors' marker is detected in output"""
        # Create spec that triggers semantic errors
        spec_content = """---- MODULE semantic_marker ----
EXTENDS Naturals

VARIABLES x

\\* Reference undefined operator
Init == x = 0 /\\ Foo

====
"""
        spec_file = tmp_path / "semantic_marker.tla"
        spec_file.write_text(spec_content)

        success, output = validator.validate(str(spec_file))

        # Should fail due to semantic error
        assert success is False
