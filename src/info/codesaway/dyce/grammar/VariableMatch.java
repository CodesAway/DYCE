package info.codesaway.dyce.grammar;

import org.eclipse.jdt.core.dom.IVariableBinding;

import info.codesaway.bex.Indexed;
import info.codesaway.bex.IndexedValue;

public class VariableMatch implements CodeMatch {

	private final Indexed<String> code;
	private final IVariableBinding binding;

	/**
	 * Create a VariableMatch indicating no match occurred
	 * @param text
	 */
	public VariableMatch(final String text) {
		this(new IndexedValue<>(0, text), null);
	}

	public VariableMatch(final Indexed<String> code, final IVariableBinding binding) {
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

	public IVariableBinding getBinding() {
		return this.binding;
	}
}
