package com.dictalab.vocabulary;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.dictalab.vocabulary.VocabularyDtos.*;
import static com.dictalab.vocabulary.VocabularyRepository.*;

@Service
@Transactional(readOnly=true)
public class ContextExerciseService {
    private final VocabularyRepository repo;
    public ContextExerciseService(VocabularyRepository repo) { this.repo=repo; }
    public Page<Map<String,Object>> list(int page) {
        var rows=repo.rows("SELECT * FROM vocab_context_exercises ORDER BY id LIMIT 21 OFFSET ?",(VocabularyContentService.page(page)-1)*20);
        return new Page<>(rows.stream().limit(20).toList(),page,20,rows.size()>20);
    }
    @Transactional(timeout=20)
    public Map<String,Object> save(long user,String key,String exerciseId,ContextEdit edit) {
        return repo.receipt(user,key,"context/"+exerciseId,edit,Map.class,()-> {
            if(edit.prompt().strip().split("\\s+").length>20) throw invalid("Câu luyện tối đa 20 từ.");
            Set<String> unique=new HashSet<>();
            String normalized=" "+RuleAnswerEvaluator.normalize(edit.prompt()).replaceAll("[^\\p{L}\\p{N}' -]"," ")+" ";
            for(var target:edit.targets()) {
                repo.note(null,target.noteId());
                if(!unique.add(target.noteId()) || !normalized.contains(" "+RuleAnswerEvaluator.normalize(target.surfaceForm())+" ")) throw invalid("Từ mục tiêu phải xuất hiện đầy đủ trong câu và không bị trùng.");
            }
            String id=exerciseId;
            if(id==null) {
                if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_context_exercises",Integer.class)>=5000) throw conflict("Đã đạt giới hạn kho câu.");
                id=id();
                repo.jdbc().update("INSERT INTO vocab_context_exercises(id,prompt,translation_vi,level,alternatives_json,published) VALUES (?,?,?,?,?,?)",
                        id,edit.prompt().strip(),edit.translationVi().strip(),edit.level(),repo.encode(edit.alternatives()),edit.published());
            } else {
                int updated=repo.jdbc().update("UPDATE vocab_context_exercises SET prompt=?,translation_vi=?,level=?,alternatives_json=?,published=?,revision=revision+1 WHERE id=? AND revision=?",
                        edit.prompt().strip(),edit.translationVi().strip(),edit.level(),repo.encode(edit.alternatives()),edit.published(),id,edit.revision());
                if(updated!=1) throw conflict("Câu đã thay đổi hoặc không tồn tại.");
                repo.jdbc().update("DELETE FROM vocab_context_targets WHERE exercise_id=?",id);
            }
            for(var target:edit.targets()) repo.jdbc().update("INSERT INTO vocab_context_targets(exercise_id,note_id,surface_form) VALUES (?,?,?)",id,target.noteId(),target.surfaceForm());
            return repo.one("SELECT * FROM vocab_context_exercises WHERE id=?",id);
        });
    }
}
