package ca.bc.gov.educ.api.penmatch.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.validation.constraints.NotNull;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class StudentMergeEntity {
  private static final long serialVersionUID = 1L;

  String studentMergeID;
  @NotNull(message = "Student ID can not be null.")
  String studentID;
  @NotNull(message = "Merge Student ID can not be null.")
  String mergeStudentID;
  @NotNull(message = "Student Merge Direction Code can not be null.")
  String studentMergeDirectionCode;
  @NotNull(message = "Student Merge Source Code can not be null.")
  String studentMergeSourceCode;

  StudentEntity mergeStudent;
}
