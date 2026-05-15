package org.epos.api.core.search;

public class QueryTerm {

    public enum Type {
        PHRASE,
        PROPER_NOUN,
        DOMAIN_TERM,
        COMMON_TERM
    }

    private final String term;
    private final Type type;
    private final double weight;
    private final boolean isPhrase;

    public QueryTerm(String term, Type type, double weight) {
        this.term = term;
        this.type = type;
        this.weight = weight;
        this.isPhrase = term.contains(" ");
    }

    public String getTerm() {
        return term;
    }

    public Type getType() {
        return type;
    }

    public double getWeight() {
        return weight;
    }

    public boolean isPhrase() {
        return isPhrase;
    }

    public boolean isProperNoun() {
        return type == Type.PROPER_NOUN;
    }

    @Override
    public String toString() {
        return "QueryTerm{term='" + term + "', type=" + type + ", weight=" + weight + "}";
    }
}
