package dev.mfp.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The amount box's expression evaluator.
 *
 * <p>Two claims matter more than the arithmetic itself: a plain number must keep working, because
 * that is what nearly every target is; and nothing may throw, because the field evaluates on every
 * keystroke and so sees every prefix of what the user meant to type.
 */
class ArithmeticTest {

    private static double value(String text) {
        OptionalDouble result = Arithmetic.evaluate(text);
        assertTrue(result.isPresent(), "\"" + text + "\" should evaluate");
        return result.getAsDouble();
    }

    private static void rejects(String text, String why) {
        assertTrue(Arithmetic.evaluate(text).isEmpty(), why);
    }

    @Test
    @DisplayName("a plain number evaluates to itself")
    void plainNumbersAreUnchanged() {
        assertEquals(12, value("12"), "the overwhelmingly common case is a bare integer");
        assertEquals(0.5, value("0.5"), "decimals are typed for fractional machine counts");
        assertEquals(0.5, value(".5"), "a leading point is a normal way to type a half");
        assertEquals(0, value("0"), "zero is a legitimate target, not an absent one");
    }

    @Test
    @DisplayName("a product of a count and a rate is the point of the feature")
    void multiplicationEvaluates() {
        assertEquals(2, value("8*0.25"), "eight machines at a quarter each is two");
    }

    @Test
    @DisplayName("multiplication binds tighter than addition")
    void precedenceFollowsArithmetic() {
        assertEquals(14, value("2+3*4"), "reading left to right would give 20 and a wrong plan");
        assertEquals(7, value("1+12/2"), "division binds like multiplication");
    }

    @Test
    @DisplayName("subtraction and division are left-associative")
    void chainsGroupToTheLeft() {
        assertEquals(5, value("10-3-2"), "right-associating would give 9");
        assertEquals(2, value("16/4/2"), "right-associating would give 8");
    }

    @Test
    @DisplayName("parentheses override precedence")
    void parenthesesGroup() {
        assertEquals(20, value("(2+3)*4"), "brackets are how a user forces the other reading");
        assertEquals(9, value("((3))*3"), "nesting is not a special case");
    }

    @Test
    @DisplayName("a leading minus negates rather than failing")
    void unaryMinusIsAccepted() {
        assertEquals(-4, value("-4"), "a negative literal must parse even if a target rejects it");
        assertEquals(2, value("6+-4"), "a sign after an operator is ordinary typing");
        assertEquals(2, value("-(-2)"), "a negated bracket is still an expression");
        assertEquals(6, value("-2*-3"), "two negatives multiply positive");
    }

    @Test
    @DisplayName("whitespace anywhere is ignored")
    void whitespaceIsTolerated() {
        assertEquals(2, value("  8 * 0.25  "), "spacing is a habit, not a syntax error");
        assertEquals(20, value("( 2 + 3 ) * 4"), "including inside brackets");
    }

    @Test
    @DisplayName("anything that is not a complete expression is quietly empty")
    void incompleteOrInvalidInputIsEmpty() {
        rejects(null, "a null field value must not throw");
        rejects("", "an empty box is not yet a number");
        rejects("   ", "nor is a box of spaces");
        rejects("8*", "a trailing operator is mid-typing, not an error to report");
        rejects("+", "an operator alone has no operands");
        rejects("(2+3", "an unclosed bracket is mid-typing too");
        rejects("2+3)", "a stray closing bracket must not evaluate to 5");
        rejects("()", "empty brackets contain no value");
        rejects("2 3", "two numbers with no operator is not multiplication");
        rejects("8x2", "a stray letter is not silently skipped");
        rejects("abc", "and neither is a word");
        rejects("1.2.3", "a second decimal point is a typo, not a version");
        rejects(".", "a lone point is not a number");
    }

    @Test
    @DisplayName("a non-finite result is empty rather than infinity")
    void divisionByZeroIsEmpty() {
        rejects("1/0", "Infinity would flow into the solver as a real target");
        rejects("0/0", "and NaN would poison every number downstream");
        rejects("-1/0", "negative infinity is no better");
    }

    @Test
    @DisplayName("the accepted alphabet is exactly what the grammar can parse")
    void expressionCharsMatchTheGrammar() {
        for (char c : "0123456789.+-*/() ".toCharArray()) {
            assertTrue(Arithmetic.isExpressionChar(c), c + " is part of an expression");
        }
        for (char c : "abxE,;\t\n%^".toCharArray()) {
            assertFalse(Arithmetic.isExpressionChar(c), c + " would type fine and then empty the box");
        }
    }
}
