package im.tlgxy.cloud.module.storage.model.request.admin;

import lombok.Data;

/** Uses only the connection and CORS rules already saved for the authorized source. */
@Data
public class ApplyStorageCorsRequest {
    private boolean confirmed;
}
