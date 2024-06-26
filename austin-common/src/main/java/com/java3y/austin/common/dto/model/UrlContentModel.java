package com.java3y.austin.common.dto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * @author 3y
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UrlContentModel extends ContentModel {
    /**
     * options
     */
    Map<String, String> Options;

    /**
     * headers
     */
    Map<String, String> Headers;

    /**
     * parameters
     */
    Map<String, String> Params;

}
