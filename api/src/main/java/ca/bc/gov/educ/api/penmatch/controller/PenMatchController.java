package ca.bc.gov.educ.api.penmatch.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.web.bind.annotation.RestController;

import ca.bc.gov.educ.api.penmatch.endpoint.PenMatchEndpoint;
import ca.bc.gov.educ.api.penmatch.service.PenMatchService;
import ca.bc.gov.educ.api.penmatch.struct.PenMatchSession;
import ca.bc.gov.educ.api.penmatch.struct.PenMatchStudent;
import lombok.AccessLevel;
import lombok.Getter;

@RestController
@EnableResourceServer
public class PenMatchController implements PenMatchEndpoint {
  @Getter(AccessLevel.PRIVATE)
  private final PenMatchService penMatchService;
//  private static final PenMatchMapper mapper = PenMatchMapper.mapper;

  @Autowired
  public PenMatchController(final PenMatchService penMatchService) {
    this.penMatchService = penMatchService;
  }
 

  @Override
  public PenMatchSession matchStudent(PenMatchStudent student) {
	return penMatchService.matchStudent(student);
  }

}
