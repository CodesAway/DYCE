package info.codesaway.dyce.grammar;

public interface CodeMatch {
	int getScore();

	default boolean isMatch() {
		return this.getScore() > 0;
	}

	String getCode();
}
