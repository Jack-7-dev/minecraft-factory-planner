package dev.mfp.core.plan;

import java.util.OptionalDouble;

/**
 * Arithmetic typed into an amount box, so that "8*0.25" is an answer and not a typo.
 *
 * <p>A target is nearly always reasoned out rather than known: eight machines at a quarter each, a
 * belt split three ways. Making the user do that sum in their head and type the result loses the
 * working, and loses it exactly where a mistake is invisible — a wrong number in a plan looks like a
 * plan.
 *
 * <p>Every failure is {@link OptionalDouble#empty()} and never an exception. This is read from a
 * text field on each keystroke, so "8*" is not bad input, it is a half-typed expression; the field
 * must be able to ask "is this a number yet?" and be told "not yet" without the GUI having to guard
 * the call. Non-finite results are empty for the same reason: {@code 1/0} is a mistake being made,
 * not a target of infinity.
 */
public final class Arithmetic {

    private Arithmetic() {}

    /** The value of a typed expression, or empty when it is not one. */
    public static OptionalDouble evaluate(String text) {
        if (text == null || text.isBlank()) {
            return OptionalDouble.empty();
        }
        Parser parser = new Parser(text);
        double value = parser.expression();
        if (parser.failed) {
            return OptionalDouble.empty();
        }
        parser.skipSpace();
        // Trailing anything means the parser stopped early — an unbalanced ')' or a stray letter.
        if (parser.at < text.length() || !Double.isFinite(value)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value);
    }

    /**
     * Whether {@code codePoint} may appear in an expression.
     *
     * <p>Lives here rather than in the text field so that the accepted alphabet and the grammar that
     * parses it cannot drift apart: a character the filter allows but the parser rejects is a key
     * that types fine and then silently empties the box.
     */
    public static boolean isExpressionChar(int codePoint) {
        return (codePoint >= '0' && codePoint <= '9')
                || codePoint == '.' || codePoint == '+' || codePoint == '-'
                || codePoint == '*' || codePoint == '/'
                || codePoint == '(' || codePoint == ')' || codePoint == ' ';
    }

    /**
     * Recursive descent over the four operators, one method per precedence level.
     *
     * <p>{@code failed} rather than a thrown parse error: the caller wants a verdict, and unwinding
     * a half-typed sum through exceptions costs more than a flag that every level checks anyway.
     */
    private static final class Parser {
        private final String text;
        private int at;
        private boolean failed;

        Parser(String text) {
            this.text = text;
        }

        /** Sums, left-associative, so {@code 10-3-2} is 5 and not 9. */
        double expression() {
            double value = term();
            while (!failed) {
                skipSpace();
                char op = peek();
                if (op != '+' && op != '-') {
                    break;
                }
                at++;
                double rhs = term();
                value = op == '+' ? value + rhs : value - rhs;
            }
            return value;
        }

        /** Products, binding tighter than sums, so {@code 2+3*4} is 14. */
        double term() {
            double value = unary();
            while (!failed) {
                skipSpace();
                char op = peek();
                if (op != '*' && op != '/') {
                    break;
                }
                at++;
                double rhs = unary();
                value = op == '*' ? value * rhs : value / rhs;
            }
            return value;
        }

        /** Leading signs, stacked, because "-(-2)" and "--2" are both things people type. */
        double unary() {
            skipSpace();
            if (peek() == '-') {
                at++;
                return -unary();
            }
            if (peek() == '+') {
                at++;
                return unary();
            }
            return atom();
        }

        /** A literal or a bracketed expression — anything else ends the parse. */
        double atom() {
            skipSpace();
            if (peek() == '(') {
                at++;
                double value = expression();
                skipSpace();
                if (peek() != ')') {
                    failed = true;
                    return 0;
                }
                at++;
                return value;
            }
            int start = at;
            while (isDigitOrDot(peek())) {
                at++;
            }
            if (at == start) {
                failed = true;
                return 0;
            }
            try {
                return Double.parseDouble(text.substring(start, at));
            } catch (NumberFormatException e) {
                // Reachable for a lone "." or a second point in one literal; both are still typing.
                failed = true;
                return 0;
            }
        }

        void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        /** Past the end reads as a character no rule accepts, which ends every loop cleanly. */
        char peek() {
            return at < text.length() ? text.charAt(at) : '\0';
        }

        private static boolean isDigitOrDot(char c) {
            return (c >= '0' && c <= '9') || c == '.';
        }
    }
}
