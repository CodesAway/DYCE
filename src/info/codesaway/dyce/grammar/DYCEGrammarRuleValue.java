package info.codesaway.dyce.grammar;

import info.codesaway.bex.matching.BEXMatcher;
import info.codesaway.bex.matching.BEXPattern;
import info.codesaway.bex.parsing.BEXString;

public class DYCEGrammarRuleValue implements DYCEGrammarRule {
	private final BEXPattern pattern;
	private final ThreadLocal<BEXMatcher> matcher;
	private final String replacement;

	public DYCEGrammarRuleValue(final String bexPattern, final String replacement) {
		this(BEXPattern.compile(bexPattern), replacement);
	}

	public DYCEGrammarRuleValue(final BEXPattern pattern, final String replacement) {
		this.pattern = pattern;
		this.matcher = ThreadLocal.withInitial(() -> pattern.matcher());
		this.replacement = replacement;
	}

	public BEXPattern getPattern() {
		return this.pattern;
	}

	@Override
	public String getReplacement() {
		return this.replacement;
	}

	private BEXMatcher getMatcher() {
		return this.matcher.get();
	}

	@Override
	public boolean accept(final BEXString text) {
		return this.getMatcher().reset(text).find();
	}

	@Override
	public String replace(final BEXString text, final String replacement) {
		return this.getMatcher().reset(text).replaceAll(replacement);
	}
}
