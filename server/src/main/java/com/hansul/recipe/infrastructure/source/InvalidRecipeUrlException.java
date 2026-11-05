package com.hansul.recipe.infrastructure.source;

public class InvalidRecipeUrlException extends RuntimeException {

    public InvalidRecipeUrlException(String message) {
        super(message);
    }
}
