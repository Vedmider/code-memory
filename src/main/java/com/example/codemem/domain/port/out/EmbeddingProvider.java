package com.example.codemem.domain.port.out;

/** Turns code text into a vector. Swap for a code-tuned model via config. */
public interface EmbeddingProvider {

    float[] embed(String text);

    int dimensions();
}
