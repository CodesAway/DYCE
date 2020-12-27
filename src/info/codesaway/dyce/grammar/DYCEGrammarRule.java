package info.codesaway.dyce.grammar;

import java.util.function.Predicate;

import info.codesaway.bex.parsing.BEXString;

public interface DYCEGrammarRule extends Predicate<BEXString> {
	boolean accept(BEXString text);

	String getReplacement();

	String replace(BEXString text, String replacement);

	// Method from Predicate interface (added as default method)
	@Override
	default boolean test(final BEXString text) {
		return this.accept(text);
	}

	default String replace(final BEXString text) {
		return this.replace(text, this.getReplacement());
	}
}
