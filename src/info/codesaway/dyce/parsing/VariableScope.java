package info.codesaway.dyce.parsing;

import info.codesaway.bex.IntRange;

public class VariableScope {
	private final String name;
	private final int position;
	private final IntRange scope;

	public VariableScope(final String name, final int position, final IntRange scope) {
		this.name = name;
		this.position = position;
		this.scope = scope;
	}

	public String getName() {
		return this.name;
	}

	public boolean isBeforePosition(final int position) {
		return this.position <= position;
	}

	public boolean isInScope(final int offset) {
		return this.scope.contains(offset);
	}
}
