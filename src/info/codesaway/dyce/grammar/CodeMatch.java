package info.codesaway.dyce.grammar;

import org.eclipse.jdt.core.dom.ITypeBinding;

public interface CodeMatch {
	int getScore();

	default boolean isMatch() {
		return this.getScore() > 0;
	}

	String getCode();

	ITypeBinding getReturnType();
}
