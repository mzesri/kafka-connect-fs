package com.github.mmolimar.kafka.connect.fs.file.reader;

import com.github.mmolimar.kafka.connect.fs.file.Offset;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.mmolimar.kafka.connect.fs.FsSourceTaskConfig.FILE_READER_PREFIX;

public class DelimitedTextFileReader extends AbstractFileReader<DelimitedTextFileReader.DelimitedRecord> {
    private static final String FILE_READER_DELIMITED = FILE_READER_PREFIX + "delimited.";
    public static final String FILE_READER_DELIMITED_HEADER = FILE_READER_DELIMITED + "header";
    public static final String FILE_READER_DELIMITED_TOKEN = FILE_READER_DELIMITED + "token";
    public static final String FILE_READER_DELIMITED_ENCODING = FILE_READER_DELIMITED + "encoding";
    public static final String FILE_READER_DELIMITED_DEFAULT_VALUE = FILE_READER_DELIMITED + "default_value";

    private static final String DEFAULT_COLUMN_NAME = "column";

    private final TextFileReader inner;
    private final Schema schema;
    private DelimitedTextOffset offset;
    private String token;
    private String defaultValue;
    private boolean hasHeader;
    private CSVParser parser;


    public DelimitedTextFileReader(FileSystem fs, Path filePath, Map<String, Object> config) throws IOException {
        super(fs, filePath, new DelimitedTxtToStruct(), config);

        parser = new CSVParserBuilder()
            .withSeparator(token.charAt(0))
            .build();

        //mapping encoding for text file reader
        if (config.get(FILE_READER_DELIMITED_ENCODING) != null) {
            config.put(TextFileReader.FILE_READER_TEXT_ENCODING, config.get(FILE_READER_DELIMITED_ENCODING));
        }
        this.inner = new TextFileReader(fs, filePath, config);
        this.offset = new DelimitedTextOffset(0, hasHeader);

        SchemaBuilder schemaBuilder = SchemaBuilder.struct();
        if (hasNext()) {
            String firstLine = inner.nextRecord().getValue();
//            String columns[] = firstLine.split(token);
            String columns[] = parser.parseLine(firstLine);
            IntStream.range(0, columns.length).forEach(index -> {
                String columnName = hasHeader ? columns[index] : DEFAULT_COLUMN_NAME + "_" + ++index;
                schemaBuilder.field(columnName, SchemaBuilder.STRING_SCHEMA);
            });

            if (!hasHeader) {
                //back to the first line
                inner.seek(this.offset);
            }
        }
        this.schema = schemaBuilder.build();
    }

    @Override
    protected void configure(Map<String, Object> config) {
        if (config.get(FILE_READER_DELIMITED_TOKEN) == null ||
                config.get(FILE_READER_DELIMITED_TOKEN).toString().equals("")) {
            throw new IllegalArgumentException(FILE_READER_DELIMITED_TOKEN + " property cannot be empty for DelimitedTextFileReader");
        }
        this.token = config.get(FILE_READER_DELIMITED_TOKEN).toString();
        this.defaultValue = config.get(FILE_READER_DELIMITED_DEFAULT_VALUE) == null ?
                null : config.get(FILE_READER_DELIMITED_DEFAULT_VALUE).toString();
        this.hasHeader = Boolean.valueOf((String) config.get(FILE_READER_DELIMITED_HEADER));
    }

    @Override
    protected DelimitedRecord nextRecord() {
        offset.inc();
//        String values[] = inner.nextRecord().getValue().split(token);
//        return new DelimitedRecord(schema, defaultValue != null ? fillNullValues(values) : values);
        String rawData = inner.nextRecord().getValue();
        try {
            String values[] = parser.parseLine(rawData);
            return new DelimitedRecord(schema, defaultValue != null ? fillNullValues(values) : values);
        }
        catch(IOException e) {
            throw new RuntimeException("Error parsing data: " + rawData);
        }
    }

    private String[] fillNullValues(final String[] values) {
        return IntStream.range(0, schema.fields().size())
                .mapToObj(index -> {
                    if (index < values.length) {
                        return values[index];
                    } else {
                        return defaultValue;
                    }
                })
                .collect(Collectors.toList())
                .toArray(new String[0]);
    }

    @Override
    public boolean hasNext() {
        return inner.hasNext();
    }

    @Override
    public void seek(Offset offset) {
        inner.seek(offset);
        this.offset.setOffset(inner.currentOffset().getRecordOffset());
    }

    @Override
    public Offset currentOffset() {
        return offset;
    }

    @Override
    public void close() throws IOException {
        inner.close();
    }

    public static class DelimitedTextOffset implements Offset {
        private long offset;
        private boolean hasHeader;

        public DelimitedTextOffset(long offset, boolean hasHeader) {
            this.hasHeader = hasHeader;
            this.offset = hasHeader && offset >= 0 ? offset + 1 : offset;
        }

        public void setOffset(long offset) {
            this.offset = hasHeader && offset > 0 ? offset - 1 : offset;
        }

        protected void inc() {
            this.offset++;
        }

        @Override
        public long getRecordOffset() {
            return offset;
        }
    }

    static class DelimitedTxtToStruct implements ReaderAdapter<DelimitedRecord> {

        @Override
        public Struct apply(DelimitedRecord record) {
            Struct struct = new Struct(record.schema);
            IntStream.range(0, record.schema.fields().size()).forEach(index -> {
                if (index < record.values.length) {
                    struct.put(record.schema.fields().get(index).name(), record.values[index]);
                }
            });
            return struct;
        }
    }

    static class DelimitedRecord {
        private final Schema schema;
        private final String[] values;

        public DelimitedRecord(Schema schema, String[] values) {
            this.schema = schema;
            this.values = values;
        }
    }
}
