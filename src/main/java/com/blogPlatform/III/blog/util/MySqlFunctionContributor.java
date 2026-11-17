package com.blogPlatform.III.blog.util;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

// registered in META-INF/services; lets Specifications use MATCH ... AGAINST via match_against(...)
public class MySqlFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions functions) {
        functions.getFunctionRegistry().registerPattern(
                "match_against",
                "match(?1, ?2) against (?3 in natural language mode)",
                functions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.DOUBLE));
    }
}
