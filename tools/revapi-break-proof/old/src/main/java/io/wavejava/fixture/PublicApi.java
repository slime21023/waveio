package io.wavejava.fixture;

/** Version 1.0 of the deliberately tiny public API used by the compatibility proof. */
public final class PublicApi {
    /** Member retained by the candidate version. */
    public String retained() {
        return "retained";
    }

    /** Member deliberately removed by the candidate version. */
    public String removed() {
        return "removed";
    }
}
