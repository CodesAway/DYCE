package info.codesaway.dyce.grammar;

import java.util.StringJoiner;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaModelException;
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
		StringJoiner result = new StringJoiner(", ", this.code.getValue() + "(", ")");

		// TODO: is this the right place to handle parameters?
		// How would I handle determining suggested parameters based on current context?
		boolean includedParameters = false;

		if (this.hasBinding()) {
			IJavaElement javaElement = this.binding.getJavaElement();

			if (javaElement instanceof IMethod) {
				try {
					IMethod method = (IMethod) javaElement;
					for (String parameterName : method.getParameterNames()) {
						result.add(parameterName);
					}
					includedParameters = true;
				} catch (JavaModelException e) {
				}
			}
		}

		if (!includedParameters) {
			// TODO: do something
		}

		return result.toString();
	}

	public IMethodBinding getBinding() {
		return this.binding;
	}

	public boolean hasBinding() {
		return this.binding != null;
	}

	@Override
	public ITypeBinding getReturnType() {
		// TODO: handle getting correct return type when call getClass method
		// (likely need to pass class which method was invoked on)

		//		if (this.hasBinding() && this.getBinding().getName().equals("getClass")
		//				&& this.getBinding().getParameterTypes().length == 0) {
		//			// Object.getClass method should return Class<? extends |X|> per documentation
		//			System.out.println("getClass method: " + this.getBinding().getReturnType().getName() + "\t"
		//					+ this.getBinding().getReturnType().getErasure().getName());
		//			System.out.println("Binding: " + BindingKey.createWildcardTypeBindingKey(
		//					this.getBinding().getReturnType().getErasure().getQualifiedName(), Signature.C_EXTENDS,
		//					this.getBinding().toString(), 0));
		//		}

		return this.hasBinding()
				? this.getBinding().getReturnType()
				: null;
	}

	@Override
	public String toString() {
		return String.format("%s%s", this.getCode(),
				this.hasBinding() ? ": " + this.getReturnType().getQualifiedName() : "");
	}
}
