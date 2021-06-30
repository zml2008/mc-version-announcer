package ca.stellardrift.mcannouncer.discord;

import com.google.gson.annotations.SerializedName;
import org.immutables.gson.Gson;
import org.immutables.value.Value;

import java.util.List;

@Gson.TypeAdapters
@Value.Immutable
public interface AllowedMention {

    static AllowedMention none() {
        return builder()
            .parse(List.of())
            .repliedUser(false)
            .build();
    }

    static Builder builder() {
        return new Builder();
    }

    List<String> parse();
    List<Integer> roles();
    List<Integer> users();
    @SerializedName("replied_user")
    boolean repliedUser();

    final class Builder extends AllowedMentionImpl.Builder {
        // auto-generated
    }

}
