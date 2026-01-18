package CFG;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import tla2sany.drivers.SANY;
import tla2sany.modanalyzer.SpecObj;
import tla2sany.semantic.ModuleNode;
import printer.CFGFuncToStr;

/**
 * SANY-based Control Flow Analysis Transformer CLI
 * Replaces the old ANTLR-based CfaTransformerCli with SANY parser integration
 */
public class SANYTransformerCli {
    
    public static void main(String[] args) throws Exception {
        
        // --- 1. Argument Validation ---
        if (args.length < 2) {
            System.err.println("ERROR: Missing arguments.");
            System.err.println("Usage: java -jar <jar_name>.jar <input_file.tla> <output_file.tla> [--show-tree] [--algorithm <algorithm>] [--debug]");
            System.err.println("       java -jar <jar_name>.jar --show-tree <input_file.tla> <output_file.tla>");
            System.err.println("       java -jar <jar_name>.jar --algorithm sa <input_file.tla> <output_file.tla>");
            System.err.println("");
            System.err.println("Algorithm options:");
            System.err.println("  --algorithm all    Run all algorithms (default)");
            System.err.println("  --algorithm cfg    Build CFG only");
            System.err.println("  --algorithm print  Build CFG and print formatted TLA+");
            System.err.println("");
            System.err.println("Debug options:");
            System.err.println("  --debug            Print detailed parsing information");
            System.err.println("  --show-tree        Show SANY AST information");
            System.err.println("  --debug-uc         Run UC stage and dump synthesized UNCHANGED nodes");
            System.exit(1);
        }
        
        // --- 2. Parse command line arguments ---
        boolean showTree = false;
        boolean debugMode = false;
        boolean debugUC = false;
        String algorithm = "print"; // Default to CFG + print
        String inputFile = null;
        String outputFile = null;
        
        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--show-tree") || args[i].equalsIgnoreCase("-show-tree")) {
                showTree = true;
            } else if (args[i].equalsIgnoreCase("--debug") || args[i].equalsIgnoreCase("-debug")) {
                debugMode = true;
            } else if (args[i].equalsIgnoreCase("--debug-uc")) {
                debugUC = true;
            } else if (args[i].equalsIgnoreCase("--algorithm") || args[i].equalsIgnoreCase("-algorithm")) {
                if (i + 1 < args.length) {
                    algorithm = args[i + 1].toLowerCase();
                    i++; // Skip the next argument since we've consumed it
                } else {
                    System.err.println("ERROR: --algorithm requires a value");
                    System.exit(1);
                }
            } else if (inputFile == null) {
                inputFile = args[i];
            } else if (outputFile == null) {
                outputFile = args[i];
            }
        }
        
        // Validate algorithm parameter
        if (!algorithm.equals("all") && !algorithm.equals("cfg") && !algorithm.equals("print")) {
            System.err.println("ERROR: Invalid algorithm: " + algorithm);
            System.err.println("Valid algorithms: all, cfg, print");
            System.exit(1);
        }
        
        // Validate that we have both input and output files
        if (inputFile == null || outputFile == null) {
            System.err.println("ERROR: Both input and output files must be specified.");
            System.err.println("Usage: java -jar <jar_name>.jar <input_file.tla> <output_file.tla> [options]");
            System.exit(1);
        }
        
        Path inputPath = Paths.get(inputFile).toAbsolutePath();
        Path outputPath = Paths.get(outputFile);
        String absoluteInputFile = inputPath.toString();
        String sourceText = Files.readString(inputPath);

        System.out.println("Processing input file: " + absoluteInputFile);
        if (showTree) {
            System.out.println("SANY AST information will be displayed");
        }
        if (debugMode) {
            System.out.println("Debug mode enabled: detailed parsing information will be printed");
        }
        
        // --- 3. SANY Parsing ---
        System.out.println("Parsing TLA+ file with SANY...");
        
        // Create SpecObj and parse using absolute path for proper module resolution
        SpecObj spec = new SpecObj(absoluteInputFile);

        // Capture SANY output if debug mode
        PrintStream sanyOutput = debugMode ? System.out : new PrintStream(new ByteArrayOutputStream());

        try {
            SANY.frontEndMain(spec, absoluteInputFile, sanyOutput);
            sanyOutput.flush();
            
            if (spec.getErrorLevel() > 0) {
                System.err.println("SANY parsing failed with errors:");
                System.err.println("Error level: " + spec.getErrorLevel());
                if (spec.getParseErrors() != null) {
                    System.err.println("Parse errors: " + spec.getParseErrors().toString());
                }
                if (spec.getSemanticErrors() != null) {
                    System.err.println("Semantic errors: " + spec.getSemanticErrors().toString());
                }
                System.exit(1);
            }
            
            System.out.println("✅ SANY parsing successful!");

            // Verify root module was found - can fail silently even with successful parse
            ModuleNode rootModule = spec.getRootModule();
            if (rootModule == null) {
                System.err.println("ERROR: SANY parsing completed but no root module was found.");
                System.err.println("This usually indicates an issue with module resolution or EXTENDS clauses.");
                System.err.println("Verify that the input file path is correct and all extended modules are accessible.");
                System.exit(1);
            }

        } catch (Exception e) {
            System.err.println("ERROR: SANY parsing failed: " + e.getMessage());
            if (debugMode) {
                e.printStackTrace();
            }
            System.exit(1);
        }
        
        // --- 4. Display SANY AST information if requested ---
        if (showTree) {
            System.out.println("\n=== SANY AST INFORMATION ===");
            ModuleNode rootModule = spec.getRootModule();
            if (rootModule != null) {
                System.out.println("Module name: " + rootModule.getName());
                System.out.println("Module location: " + rootModule.getLocation());
                System.out.println("Tree node: " + rootModule.getTreeNode());
                System.out.println("\n--- AST Tree Structure ---");
                printASTTree(rootModule.getTreeNode(), 0);
            } else {
                System.out.println("No root module found");
            }
            System.out.println("=== END SANY AST INFORMATION ===\n");
        }
        
        // --- 5. CFG Building ---
        System.out.println("Building CFG with SANYCFGBuilder...");
        
        SANYCFGBuilder cfgBuilder = new SANYCFGBuilder();
        
        CFGCALLGraph callGraph = null;
        try {
            ModuleNode rootModule = spec.getRootModule();
            if (rootModule != null) {
                // Build CFG from SANY AST
                cfgBuilder.buildCFG(rootModule, sourceText);
                
                System.out.println("✅ CFG building successful!");
                System.out.println("Found " + cfgBuilder.getCfgFuncNodes().size() + " operators");
                System.out.println("Found " + cfgBuilder.getVariables().size() + " variables");
                System.out.println("Found " + cfgBuilder.getConstants().size() + " constants");
                
                if (debugMode) {
                    System.out.println("\nVariables: " + cfgBuilder.getVariables());
                    System.out.println("Constants: " + cfgBuilder.getConstants());
                    System.out.println("Operators: ");
                    for (CFGFuncNode func : cfgBuilder.getCfgFuncNodes()) {
                        System.out.println("  - " + func.getFuncName() + "(" + String.join(", ", func.getParameters()) + ")");
                    }
                }

                // Add auxiliary variables used by PC algorithm before building call graph
                // This ensures UC algorithm will include them in UNCHANGED statements
                List<String> variables = cfgBuilder.getVariables();
                variables.add("pc");
                variables.add("stack");
                variables.add("info");

                callGraph = new CFGCALLGraph(cfgBuilder.getCfgFuncNodes(), variables, cfgBuilder.getConstants());
                callGraph.buildCallGraph(debugMode);
                
            } else {
                System.err.println("ERROR: No root module found in parsed spec");
                System.exit(1);
            }
            
        } catch (Exception e) {
            System.err.println("ERROR: CFG building failed: " + e.getMessage());
            if (debugMode) {
                e.printStackTrace();
            }
            System.exit(1);
        }
        
        if (debugMode && callGraph != null) {
            System.out.println("\n=== SA DEBUG: IN/OUT variable sets ===");
            CFGVarChangeAnalyzer saAnalyzer = new CFGVarChangeAnalyzer(callGraph);
            saAnalyzer.analyze_only_sa();
            saAnalyzer.printInOutVars();
            System.out.println("=== END SA DEBUG ===\n");
            
            if (debugUC) {
                System.out.println("=== UC DEBUG: inserting UNCHANGED statements ===");
                saAnalyzer.analyze_only_uc();
                saAnalyzer.printInOutVars();
                for (CFGFuncNode func : callGraph.getFuncNodes()) {
                    System.out.println("\nFunction CFG after UC: " + func.getFuncName());
                    System.out.println(func.getRoot().printTree());
                }
                System.out.println("=== END UC DEBUG ===\n");
            }
        }
        
        if (!debugMode && debugUC && callGraph != null) {
            CFGVarChangeAnalyzer ucAnalyzer = new CFGVarChangeAnalyzer(callGraph);
            ucAnalyzer.analyze_only_uc();
        }
        
        // --- 6. Run selected algorithm ---
        String resultString = "";
        CFGVarChangeAnalyzer fullAnalyzer = null;
        
        if (!algorithm.equals("cfg")) {
            fullAnalyzer = new CFGVarChangeAnalyzer(callGraph);
            fullAnalyzer.analyze();
            cfgBuilder.setCfgFuncNodes(callGraph.getFuncNodes());
        }
        
        System.out.println("Running algorithm: " + algorithm);
        switch (algorithm) {
            case "cfg":
                System.out.println("CFG building completed. No output generated.");
                resultString = "% CFG building completed successfully\n% " + cfgBuilder.getCfgFuncNodes().size() + " operators processed\n";
                break;
                
            case "print":
                System.out.println("Building formatted TLA+ output...");
                resultString = buildFormattedOutput(cfgBuilder, debugMode);
                break;
                
            case "all":
                System.out.println("Running full analysis (CFG + formatting)...");
                resultString = buildFormattedOutput(cfgBuilder, debugMode);
                // TODO: Add more analysis algorithms here when implemented
                break;
                
            default:
                System.err.println("ERROR: Unknown algorithm: " + algorithm);
                System.exit(1);
        }
        
        // --- 7. Write result to file ---
        try {
            Files.writeString(outputPath, resultString);
            System.out.println("✅ Processing finished. Output written to: " + outputPath);
            
        } catch (IOException e) {
            System.err.println("ERROR: Failed to write output file: " + e.getMessage());
            System.exit(1);
        }
    }
    
    /**
     * Build formatted TLA+ output from CFG
     */
    private static String buildFormattedOutput(SANYCFGBuilder cfgBuilder, boolean debugMode) {
        StringBuilder result = new StringBuilder();

        List<String> modulePrelude = cfgBuilder.getModulePrelude();
        boolean wrotePrelude = false;
        if (!modulePrelude.isEmpty()) {
            for (String snippet : modulePrelude) {
                if (snippet == null) {
                    continue;
                }
                String trimmed = snippet.trim();
                if (trimmed.isEmpty() || "====".equals(trimmed)) {
                    continue;
                }

                // Skip CONSTANTS declaration - we'll generate it ourselves
                if (trimmed.startsWith("CONSTANTS")) {
                    continue;
                }

                appendSnippet(result, snippet);
                wrotePrelude = true;
            }
            if (wrotePrelude) {
                appendBlankLine(result);
            }
        }

        // Add CONSTANTS declaration (from cfgBuilder, which includes Nil)
        List<String> allConstants = new ArrayList<>(cfgBuilder.getConstants());
        if (!allConstants.isEmpty()) {
            result.append("CONSTANTS\n");
            for (int i = 0; i < allConstants.size(); i++) {
                result.append("    ").append(allConstants.get(i));
                if (i < allConstants.size() - 1) {
                    result.append(",");
                }
                result.append("\n");
            }
            appendBlankLine(result);
        }

        // Add VARIABLES declaration (original variables + auxiliary variables pc, stack, info)
        result.append("VARIABLES\n");
        List<String> allVariables = new ArrayList<>(cfgBuilder.getVariables());

        for (int i = 0; i < allVariables.size(); i++) {
            result.append("    ").append(allVariables.get(i));
            if (i < allVariables.size() - 1) {
                result.append(",");
            }
            result.append("\n");
        }
        appendBlankLine(result);

        // Add header comment
        result.append("\\* Generated by SANY CFG Transformer\n");
        result.append("\\* Original operators reconstructed from Control Flow Graph\n");
        appendBlankLine(result);
        
        // Process each function
        CFGFuncToStr funcToStr = new CFGFuncToStr();
        
        for (CFGFuncNode funcNode : cfgBuilder.getCfgFuncNodes()) {
            if (debugMode) {
                System.out.println("Processing function: " + funcNode.getFuncName());
            }
            
            try {
                List<String> funcLines = funcToStr.CFGFuncToStr(funcNode);
                
                // Add function to result
                for (String line : funcLines) {
                    result.append(line).append("\n");
                }
                result.append("\n"); // Add blank line between functions
                
            } catch (Exception e) {
                System.err.println("WARNING: Failed to process function " + funcNode.getFuncName() + ": " + e.getMessage());
                if (debugMode) {
                    e.printStackTrace();
                }
            }
        }

        // === Add Init/Next/Spec for TLC model checking ===
        result.append("\n\\* === TLC Model Checking Support ===\n\n");

        // Generate Init - initial state with type-based defaults
        result.append("Init ==\n");
        for (int i = 0; i < allVariables.size(); i++) {
            String var = allVariables.get(i);
            String defaultVal = getDefaultValue(var, allConstants);
            result.append("    /\\ ").append(var).append(" = ").append(defaultVal).append("\n");
        }

        // Generate Next - disjunction of entry point actions
        List<String> entryPoints = getEntryPointActions(cfgBuilder.getCfgFuncNodes());
        if (!entryPoints.isEmpty()) {
            result.append("\nNext ==\n");
            for (int i = 0; i < entryPoints.size(); i++) {
                String prefix = (i == 0) ? "       " : "    \\/ ";
                result.append(prefix).append(entryPoints.get(i)).append("\n");
            }
        } else {
            // Fallback if no Handle* actions found - use all parameterless operators
            result.append("\nNext ==\n");
            result.append("    \\* TODO: Add state transition actions here\n");
            result.append("    FALSE\n");
        }

        // Generate Spec - temporal formula
        result.append("\nSpec == Init /\\ [][Next]_vars\n");

        // Add footer
        result.append("\n\\* End of generated TLA+ specification\n");

        List<String> modulePostlude = cfgBuilder.getModulePostlude();
        if (!modulePostlude.isEmpty()) {
            appendBlankLine(result);
            for (String snippet : modulePostlude) {
                appendSnippet(result, snippet);
            }
        }
        
        return result.toString();
    }
    
    private static void appendSnippet(StringBuilder builder, String snippet) {
        if (snippet == null || snippet.isEmpty()) {
            return;
        }
        builder.append(snippet);
        if (!snippet.endsWith("\n")) {
            builder.append("\n");
        }
    }

    private static void appendBlankLine(StringBuilder builder) {
        ensureTrailingNewline(builder);
        builder.append("\n");
    }

    private static void ensureTrailingNewline(StringBuilder builder) {
        if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '\n') {
            builder.append("\n");
        }
    }

    /**
     * Get default value for a variable based on its name pattern.
     * Uses type-based heuristics - Step 3 LLM can refine if needed.
     */
    private static String getDefaultValue(String varName, List<String> constants) {
        // Auxiliary variables from PC algorithm
        if (varName.equals("pc")) {
            return "\"Start\"";
        }
        if (varName.equals("stack")) {
            return "<<>>";
        }
        if (varName.equals("info")) {
            return "[args |-> <<>>]";
        }

        // Message-related variables - typically sets
        if (varName.equals("messages")) {
            return "{}";
        }
        if (varName.equals("votes")) {
            return "[s \\in Server |-> {}]";
        }

        // Log is a sequence
        if (varName.equals("log")) {
            return "[s \\in Server |-> <<>>]";
        }

        // Index variables - integers starting at 0 or 1
        if (varName.endsWith("Index") || varName.equals("commitIndex")) {
            return "[s \\in Server |-> 0]";
        }

        // Elapsed counters
        if (varName.endsWith("Elapsed")) {
            return "[s \\in Server |-> 0]";
        }

        // Boolean-like variables
        if (varName.equals("preVote") || varName.equals("isLearner")) {
            return "[s \\in Server |-> FALSE]";
        }

        // State variable (Raft-specific)
        if (varName.equals("state")) {
            return "[s \\in Server |-> StateFollower]";
        }

        // Term is an integer
        if (varName.equals("currentTerm")) {
            return "[s \\in Server |-> 0]";
        }

        // Variables that reference None/Nil
        if (varName.equals("votedFor") || varName.equals("leaderId") || varName.equals("leadTransferee")) {
            // Check if None is in constants
            if (constants.contains("None")) {
                return "[s \\in Server |-> None]";
            }
            return "[s \\in Server |-> Nil]";
        }

        // Size/count variables
        if (varName.endsWith("Size") || varName.endsWith("Timeout")) {
            return "[s \\in Server |-> 0]";
        }

        // Default: function mapping Server to 0
        return "[s \\in Server |-> 0]";
    }

    /**
     * Get list of entry point actions (Handle* operators without parameters).
     * These form the disjuncts of the Next relation.
     */
    private static List<String> getEntryPointActions(List<CFGFuncNode> funcNodes) {
        List<String> entryPoints = new ArrayList<>();

        for (CFGFuncNode func : funcNodes) {
            String name = func.getFuncName();
            // Entry points are Handle* operators with no parameters
            if (name.startsWith("Handle") && func.getParameters().isEmpty()) {
                entryPoints.add(name);
            }
        }

        return entryPoints;
    }

    /**
     * Print AST tree structure recursively
     */
    private static void printASTTree(tla2sany.st.TreeNode node, int depth) {
        if (node == null) return;
        
        // Print indentation
        String indent = "  ".repeat(depth);
        
        // Print current node information
        if (node instanceof tla2sany.parser.SyntaxTreeNode) {
            tla2sany.parser.SyntaxTreeNode stn = (tla2sany.parser.SyntaxTreeNode) node;
            System.out.println(indent + "SyntaxTreeNode: kind=" + stn.getKind() + 
                             ", image=" + stn.getImage() + 
                             ", toString=" + stn.toString());
        } else {
            System.out.println(indent + "TreeNode: " + node.toString());
        }
        
        // Print children recursively
        tla2sany.st.TreeNode[] children = node.heirs();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                System.out.println(indent + "  Child[" + i + "]:");
                printASTTree(children[i], depth + 2);
            }
        }
    }
}
