package com.falcon.snap.engine;

/** A required model file is not installed. The message names what is missing. */
public class ModelsMissingException extends Exception {
    public ModelsMissingException(String message) {
        super(message);
    }
}
