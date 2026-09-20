package org.json;

/** Thrown when JSON cannot be parsed, built or coerced. */
public class JSONException extends Exception {
	public JSONException(String s) {
		super(s);
	}

	public JSONException(String s, Throwable cause) {
		super(s, cause);
	}

	public JSONException(Throwable cause) {
		super(cause == null ? null : cause.toString(), cause);
	}
}
