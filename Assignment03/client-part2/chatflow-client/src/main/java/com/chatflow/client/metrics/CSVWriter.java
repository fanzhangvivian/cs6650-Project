package com.chatflow.client.metrics;

import com.chatflow.client.model.MessageRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * CSV writer for message records
 */
public class CSVWriter {
    
    private final String filePath;
    
    public CSVWriter(String filePath) {
        this.filePath = filePath;
    }
    
    /**
     * Write message records to CSV file
     * Format: timestamp, messageType, latencyMs, statusCode, roomId
     */
    public void writeRecords(List<MessageRecord> records) throws IOException {
        try (FileWriter writer = new FileWriter(filePath);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT
                 .withHeader("timestamp", "messageType", "latencyMs", "statusCode", "roomId"))) {
            
            for (MessageRecord record : records) {
                printer.printRecord(
                    record.getTimestamp(),
                    record.getMessageType(),
                    record.getLatencyMs(),
                    record.getStatusCode(),
                    record.getRoomId()
                );
            }
            
            System.out.println("✅ CSV file saved: " + filePath);
            System.out.println("   Total records: " + records.size());
            
        } catch (IOException e) {
            System.err.println("❌ Error writing CSV file: " + e.getMessage());
            throw e;
        }
    }
}