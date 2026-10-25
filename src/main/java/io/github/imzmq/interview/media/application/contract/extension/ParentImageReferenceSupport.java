package io.github.imzmq.interview.media.application.contract.extension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Media-owned extension for reading and replacing image references stored with parent knowledge documents.
 */
public interface ParentImageReferenceSupport {

    List<ParentImageReferences> findByFilePaths(Collection<String> filePaths);

    void replaceByParentIds(Map<String, List<String>> imageIdsByParentId);

    record ParentImageReferences(String parentId, String filePath, List<String> imageIds) {
        public ParentImageReferences {
            imageIds = imageIds == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(imageIds));
        }
    }
}
