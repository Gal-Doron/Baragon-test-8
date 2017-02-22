import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';

import { refresh } from '../../actions/ui/services';
import rootComponent from '../../rootComponent';

import ServicesTable from './ServicesTable';

class Services extends Component {
  static propTypes = {
    services: PropTypes.array,
    allowEdit: PropTypes.bool,
  };

  state = {
    filter: ''
  };

  handleSearch = (evt) => {
    this.setState({filter: evt.target.value});
  }

  render() {
    return (
      <div>
        <h1>Services</h1>
        <div className="row">
          <div className="col-md-5">
            <div className="input-group">
              <label>
                Search:
                <input
                  type="search"
                  className="form-control"
                  onKeyUp={this.handleSearch}
                />
              </label>
            </div>
          </div>
        </div>
        <div className="row">
          <div className="col-md-12">
            <ServicesTable
              services={this.props.services}
              filter={this.state.filter}
              editable={this.props.allowEdit}
            />
          </div>
        </div>
      </div>
    );
  }
}

export default connect((state) => ({
  services: state.api.services.data,
  allowEdit: config.allowEdit,
}))(rootComponent(Services, refresh));
