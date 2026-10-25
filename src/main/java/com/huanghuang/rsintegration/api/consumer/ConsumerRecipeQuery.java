package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

public sealed interface ConsumerRecipeQuery {
    record All() implements ConsumerRecipeQuery {}

    record ByRecipeId(
            ResourceLocation recipeId
    ) implements ConsumerRecipeQuery {
        public ByRecipeId {
            Objects.requireNonNull(recipeId, "recipeId");
        }
    }

    record ByOutput(
            IntegrationResourceKey output,
            ResourceMatchMode matchMode
    ) implements ConsumerRecipeQuery {
        public ByOutput {
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(matchMode, "matchMode");
        }
    }

    record ByOutputs(
            List<IntegrationResourceKey> outputs,
            ResourceMatchMode matchMode
    ) implements ConsumerRecipeQuery {
        public ByOutputs {
            if (outputs != null
                    && outputs.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "outputs must not contain null");
            }
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            if (outputs.isEmpty() || matchMode == null) {
                throw new IllegalArgumentException(
                        "outputs and matchMode are required");
            }
        }
    }

    record ByProcess(
            ResourceLocation processId
    ) implements ConsumerRecipeQuery {
        public ByProcess {
            Objects.requireNonNull(processId, "processId");
        }
    }
}
