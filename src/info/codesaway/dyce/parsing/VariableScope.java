package info.codesaway.dyce.parsing;

import org.eclipse.jdt.core.dom.IVariableBinding;

import info.codesaway.bex.IntRange;

public class VariableScope {
	private final String name;
	private final int position;
	private final IntRange scope;
	private final IVariableBinding binding;

	public VariableScope(final String name, final int position, final IntRange scope, final IVariableBinding binding) {
		this.name = name;
		this.position = position;
		this.scope = scope;
		this.binding = binding;
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

	public IVariableBinding getBinding() {
		return this.binding;
	}
}
