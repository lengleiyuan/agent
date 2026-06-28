package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 数学计算器工具 — 纯 Java 实现的表达式求值器。
 * 支持加减乘除、括号、取模、幂运算。
 */
public class CalculatorTool extends ToolBase {

    public CalculatorTool() {
        declareStringParam("expression", "数学表达式，如 2+3*4", true);
    }

    @Override
    public String getName() { return "calculator"; }

    @Override
    public String getDescription() { return "计算数学表达式，支持加减乘除、括号、幂运算等"; }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String expr = params.getString("expression");
            if (expr == null || expr.isBlank())
                return ToolResult.failure("表达式不能为空");
            if (expr.contains("System") || expr.contains("Runtime")
                || expr.contains("exec") || expr.contains("ProcessBuilder")
                || expr.contains("ClassLoader") || expr.contains("forName"))
                return ToolResult.failure("表达式包含不安全的操作");

            try {
                double result = new MathEvaluator(expr).eval();
                if (result == Math.floor(result) && !Double.isInfinite(result))
                    return ToolResult.success(String.valueOf((long) result));
                return ToolResult.success(String.valueOf(result));
            } catch (Exception e) {
                return ToolResult.failure("计算错误: " + e.getMessage());
            }
        });
    }

    /** 递归下降数学表达式求值器 */
    private static class MathEvaluator {
        private final String expr;
        private int pos;

        MathEvaluator(String expr) { this.expr = expr; this.pos = 0; }

        double eval() {
            double val = parseAddSub();
            if (pos < expr.length())
                throw new IllegalArgumentException("表达式在位置 " + pos + " 处有意外的字符: '" + expr.charAt(pos) + "'");
            return val;
        }

        private double parseAddSub() {
            double left = parseMulDiv();
            while (pos < expr.length()) {
                char c = expr.charAt(pos);
                if (c == '+') { pos++; left += parseMulDiv(); }
                else if (c == '-') { pos++; left -= parseMulDiv(); }
                else break;
            }
            return left;
        }

        private double parseMulDiv() {
            double left = parsePower();
            while (pos < expr.length()) {
                char c = expr.charAt(pos);
                if (c == '*') { pos++; char n = pos < expr.length() ? expr.charAt(pos) : 0;
                    if (n == '*') { pos++; left = Math.pow(left, parseUnary()); }
                    else left *= parseUnary();
                }
                else if (c == '/') { pos++; left /= parseUnary(); }
                else if (c == '%') { pos++; left %= parseUnary(); }
                else break;
            }
            return left;
        }

        private double parsePower() {
            double left = parseUnary();
            while (pos < expr.length() && expr.charAt(pos) == '^') {
                pos++; left = Math.pow(left, parseUnary());
            }
            return left;
        }

        private double parseUnary() {
            if (pos < expr.length() && expr.charAt(pos) == '-') {
                pos++; return -parseAtom();
            }
            if (pos < expr.length() && expr.charAt(pos) == '+') {
                pos++; return parseAtom();
            }
            return parseAtom();
        }

        private double parseAtom() {
            skipSpace();
            if (pos >= expr.length()) throw new IllegalArgumentException("意外结束");

            char c = expr.charAt(pos);
            if (c == '(') {
                pos++; double val = parseAddSub();
                skipSpace();
                if (pos >= expr.length() || expr.charAt(pos) != ')')
                    throw new IllegalArgumentException("缺少闭合括号 ')'");
                pos++; return val;
            }
            if (c == 'p' && expr.startsWith("pi", pos)) { pos += 2; return Math.PI; }
            if (c == 'e' && (pos + 1 >= expr.length() || !Character.isLetter(expr.charAt(pos + 1)))) {
                pos++; return Math.E;
            }

            // functions
            if (Character.isLetter(c)) {
                String name = readName();
                skipSpace();
                if (pos >= expr.length() || expr.charAt(pos) != '(')
                    throw new IllegalArgumentException("函数 " + name + " 缺少 '('");
                pos++; double arg = parseAddSub();
                skipSpace();
                if (pos >= expr.length() || expr.charAt(pos) != ')')
                    throw new IllegalArgumentException("函数 " + name + " 缺少 ')'");
                pos++;
                return switch (name) {
                    case "sqrt" -> Math.sqrt(arg);
                    case "abs" -> Math.abs(arg);
                    case "sin" -> Math.sin(Math.toRadians(arg));
                    case "cos" -> Math.cos(Math.toRadians(arg));
                    case "tan" -> Math.tan(Math.toRadians(arg));
                    case "log" -> Math.log10(arg);
                    case "ln" -> Math.log(arg);
                    case "ceil" -> Math.ceil(arg);
                    case "floor" -> Math.floor(arg);
                    case "round" -> Math.round(arg);
                    default -> throw new IllegalArgumentException("未知函数: " + name);
                };
            }

            return parseNumber();
        }

        private double parseNumber() {
            skipSpace();
            int start = pos;
            while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.'))
                pos++;
            if (start == pos) throw new IllegalArgumentException("位置 " + pos + " 处期望数字");
            return Double.parseDouble(expr.substring(start, pos));
        }

        private String readName() {
            int start = pos;
            while (pos < expr.length() && Character.isLetter(expr.charAt(pos))) pos++;
            return expr.substring(start, pos);
        }

        private void skipSpace() {
            while (pos < expr.length() && Character.isWhitespace(expr.charAt(pos))) pos++;
        }
    }
}
