package info.codesaway.dyce.grammar;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;

import info.codesaway.bex.Indexed;
import info.codesaway.bex.IndexedValue;

public class MethodMatch implements CodeMatch {

	private final Indexed<String> code;
	private final IMethodBinding binding;

	/**
	 * Create a MethodMatch indicating no match occurred
	 * @param text
	 */
	public MethodMatch(final String text) {
		this(new IndexedValue<>(0, text), null);
	}

	public MethodMatch(final Indexed<String> code, final IMethodBinding binding) {
		this.code = code;
		this.binding = binding;
	}

	@Override
	public int getScore() {
		return this.code.getIndex();
	}

	@Override
	public String getCode() {
		return this.code.getValue();
	}

	public IMethodBinding getBinding() {
		return this.binding;
	}

	public boolean hasBinding() {
		return this.binding != null;
	}

	@Override
	public ITypeBinding getReturnType() {
		return this.hasBinding()
				? this.getBinding().getReturnType()
				: null;
	}

	@Override
	public String toString() {
		return String.format("%s%s", this.getCode(),
				this.hasBinding() ? String.format("(%s)", this.getReturnType().getQualifiedName()) : "");
	}
}
