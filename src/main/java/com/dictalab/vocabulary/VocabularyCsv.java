package com.dictalab.vocabulary;

import java.util.*;
import static com.dictalab.vocabulary.VocabularyRepository.invalid;

/** Bounded RFC 4180 text parser. No HTML, media, or executable fields. */
public final class VocabularyCsv {
    private VocabularyCsv() {}
    public static final List<String> HEADER=List.of("term","meaning_vi","part_of_speech","example_en","example_vi","tags");
    public static List<List<String>> parse(String input) {
        if(input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2*1024*1024) throw invalid("CSV vượt quá 2 MB.");
        String text=input.startsWith("\uFEFF")?input.substring(1):input;
        List<List<String>> rows=new ArrayList<>(); List<String> row=new ArrayList<>(); StringBuilder cell=new StringBuilder();
        boolean quoted=false,closed=false;
        for(int index=0;index<text.length();index++) {
            char c=text.charAt(index);
            if(c==0) throw invalid("CSV chứa ký tự không hợp lệ.");
            if(quoted) {
                if(c=='"') {
                    if(index+1<text.length() && text.charAt(index+1)=='"') { cell.append('"'); index++; }
                    else { quoted=false; closed=true; }
                } else cell.append(c);
            } else if(c==',' || c=='\r' || c=='\n') {
                row.add(cell.toString()); cell.setLength(0); closed=false;
                if(c!=',') {
                    if(c=='\r' && index+1<text.length() && text.charAt(index+1)=='\n') index++;
                    if(row.stream().anyMatch(value->!value.isBlank())) rows.add(List.copyOf(row)); row.clear();
                    if(rows.size()>1001) throw invalid("CSV tối đa 1.000 mục từ.");
                }
            } else if(c=='"') {
                if(cell.length()!=0 || closed) throw invalid("Dấu nháy CSV không hợp lệ."); quoted=true;
            } else {
                if(closed) throw invalid("Ký tự sau dấu nháy đóng không hợp lệ."); cell.append(c);
            }
            if(cell.length()>4000 || row.size()>6) throw invalid("Ô hoặc số cột CSV vượt giới hạn.");
        }
        if(quoted) throw invalid("CSV thiếu dấu nháy đóng.");
        row.add(cell.toString());
        if(row.stream().anyMatch(value->!value.isBlank())) rows.add(List.copyOf(row));
        if(rows.isEmpty() || !rows.get(0).equals(HEADER)) throw invalid("Header CSV phải là: "+String.join(",",HEADER));
        if(rows.size()>1001) throw invalid("CSV tối đa 1.000 mục từ.");
        return rows.subList(1,rows.size());
    }
    public static String row(List<String> values) {
        return values.stream().map(value->{
            String safe=value;
            if(!safe.isEmpty() && ("=+-@\t\r\n".indexOf(safe.charAt(0))>=0 || (!safe.stripLeading().isEmpty() && "=+-@".indexOf(safe.stripLeading().charAt(0))>=0))) safe="'"+safe;
            return "\""+safe.replace("\"","\"\"")+"\"";
        }).collect(java.util.stream.Collectors.joining(","))+"\r\n";
    }
}
