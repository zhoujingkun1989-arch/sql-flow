package io.sqlflow.contracts.lineage.xml;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import java.io.IOException;

/**
 * Thread-safe Jackson {@link XmlMapper} codec for {@code <dlineage>} XML ({@code 03 §1.3}).
 */
public final class DlineageXmlCodec {

    private static final XmlMapper XML_MAPPER = createXmlMapper();

    private DlineageXmlCodec() {
    }

    public static XmlMapper xmlMapper() {
        return XML_MAPPER;
    }

    public static String toXml(DlineageXml dlineage) {
        return toXml(dlineage, false);
    }

    public static String toXml(DlineageXml dlineage, boolean includeXmlDeclaration) {
        try {
            if (includeXmlDeclaration) {
                return XML_MAPPER
                        .writer()
                        .with(ToXmlGenerator.Feature.WRITE_XML_DECLARATION)
                        .with(SerializationFeature.INDENT_OUTPUT)
                        .writeValueAsString(dlineage);
            }
            return XML_MAPPER
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(dlineage);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize <dlineage> XML", e);
        }
    }

    public static DlineageXml fromXml(String xml) {
        try {
            return XML_MAPPER.readValue(xml, DlineageXml.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse <dlineage> XML", e);
        }
    }

    private static XmlMapper createXmlMapper() {
        XmlMapper mapper = XmlMapper.builder()
                .defaultUseWrapper(false)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .build();
        return mapper;
    }
}
